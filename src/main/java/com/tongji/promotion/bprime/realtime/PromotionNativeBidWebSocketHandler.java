package com.tongji.promotion.bprime.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 高活动竞价原生传输：每连接使用关键反馈优先、公共状态可覆盖的单写泵。
 *
 * <p>ACK 和 outcome 进入有界 FIFO；房间公共状态只保留最新批次。关键队列过载会关闭连接，客户端可凭
 * commandId 和 snapshot 恢复，任何关键反馈都不会被静默丢弃。</p>
 *
 * @since 2026-08-09
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionNativeBidWebSocketHandler extends TextWebSocketHandler {

    private static final CloseStatus BACKPRESSURE_CLOSE = new CloseStatus(4000, "backpressure");
    private static final CloseStatus SERVER_ERROR_CLOSE = new CloseStatus(1011, "server_error");
    private static final int WRITE_BATCH_SIZE = 64;

    private final PromotionBidWebSocketProtocolService protocolService;
    private final ObjectMapper objectMapper;
    private final ObjectReader requestReader;
    private final ObjectReader subscriptionReader;
    private final ObjectWriter ackWriter;
    private final ObjectWriter outcomeWriter;
    private final ObjectWriter publicEventWriter;
    private final ObjectWriter subscriptionAckWriter;
    private final TaskExecutor outboundExecutor;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final int sessionQueueCapacity;
    private final Map<String, SessionWriter> sessionWriters = new ConcurrentHashMap<>();
    private final Map<String, Set<SessionWriter>> userSessions = new ConcurrentHashMap<>();

    public PromotionNativeBidWebSocketHandler(
            PromotionBidWebSocketProtocolService protocolService,
            ObjectMapper objectMapper,
            @Qualifier("promotionBidWebSocketOutboundExecutor") TaskExecutor outboundExecutor,
            PromotionPerformanceMetrics performanceMetrics,
            PromotionBPrimeProperties properties) {
        this.protocolService = protocolService;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.readerFor(PromotionWebSocketBidRequest.class);
        this.subscriptionReader = objectMapper.readerFor(PromotionNativeSubscriptionRequest.class);
        this.ackWriter = objectMapper.writerFor(PromotionWebSocketBidAck.class);
        this.outcomeWriter = objectMapper.writerFor(PromotionAuctionOutcomeEvent.class);
        this.publicEventWriter = objectMapper.writerFor(PromotionAuctionRealtimeEvent.class);
        this.subscriptionAckWriter = objectMapper.writerFor(PromotionNativeSubscriptionAck.class);
        this.outboundExecutor = outboundExecutor;
        this.performanceMetrics = performanceMetrics;
        this.sessionQueueCapacity = properties.getWebSocketNativeSessionQueueCapacity();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionWriter writer = new SessionWriter(session, principalName(session.getPrincipal()));
        sessionWriters.put(session.getId(), writer);
        userSessions.computeIfAbsent(writer.userName(), ignored -> ConcurrentHashMap.newKeySet()).add(writer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionWriter writer = sessionWriters.get(session.getId());
        if (writer == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            if (PromotionNativeSubscriptionRequest.SUBSCRIBE.equals(root.path("type").asText())) {
                subscribe(writer, subscriptionReader.readValue(root));
                return;
            }
            submitBid(session, writer, requestReader.readValue(root));
        } catch (IOException exception) {
            writer.offerCritical(serialize(ackWriter,
                    protocolService.rejected(null, ErrorCode.BAD_REQUEST.getCode()), writer));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionWriter writer = sessionWriters.remove(session.getId());
        if (writer != null) {
            removeUserSession(writer);
            writer.dispose();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SessionWriter writer = sessionWriters.get(session.getId());
        if (writer != null) {
            writer.requestClose(SERVER_ERROR_CLOSE);
        }
    }

    /**
     * 将最终竞价结果发送到该用户的全部原生连接。
     *
     * @param event 已持久化的私有结果，不允许为 {@code null}
     * @return 接受该关键消息的连接数量
     */
    public int publishOutcome(PromotionAuctionOutcomeEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Set<SessionWriter> sessions = userSessions.get(String.valueOf(event.bidderUserId()));
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        TextMessage message = serialize(outcomeWriter, event, null);
        if (message == null) {
            return 0;
        }
        int delivered = 0;
        for (SessionWriter writer : sessions) {
            if (writer.offerCritical(message)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 将可恢复的公共状态写入对应房间连接的可覆盖槽位。
     *
     * @param event 已合并的公共房间事件，不允许为 {@code null}
     * @return 接受该公共状态的连接数量
     */
    public int publishPublic(PromotionAuctionRealtimeEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        TextMessage message = serialize(publicEventWriter, event, null);
        if (message == null) {
            return 0;
        }
        int delivered = 0;
        for (SessionWriter writer : sessionWriters.values()) {
            if (!writer.isSubscribedTo(event.auctionWindowId())) {
                continue;
            }
            boolean accepted = PromotionAuctionRealtimeEvent.WINDOW_CLOSED.equals(event.eventType())
                    ? writer.offerTerminal(message)
                    : writer.offerPublic(message);
            if (accepted) {
                delivered++;
            }
        }
        return delivered;
    }

    private void subscribe(SessionWriter writer, PromotionNativeSubscriptionRequest request) {
        if (request.auctionWindowId() <= 0) {
            writer.offerCritical(serialize(ackWriter,
                    protocolService.rejected(null, ErrorCode.BAD_REQUEST.getCode()), writer));
            return;
        }
        writer.subscribe(request.auctionWindowId());
        writer.offerCritical(serialize(subscriptionAckWriter, new PromotionNativeSubscriptionAck(
                PromotionNativeSubscriptionAck.SUBSCRIBED,
                String.valueOf(request.auctionWindowId())), writer));
    }

    private void submitBid(WebSocketSession session, SessionWriter writer, PromotionWebSocketBidRequest request) {
        protocolService.submit(request, session.getPrincipal())
                .whenComplete((ack, exception) -> {
                    if (exception == null) {
                        writer.offerCritical(serialize(ackWriter, ack, writer));
                    } else {
                        writer.requestClose(SERVER_ERROR_CLOSE);
                    }
                });
    }

    private TextMessage serialize(ObjectWriter writer, Object value, SessionWriter sessionWriter) {
        try {
            return new TextMessage(writer.writeValueAsString(value));
        } catch (IOException exception) {
            if (sessionWriter != null) {
                sessionWriter.requestClose(SERVER_ERROR_CLOSE);
            }
            return null;
        }
    }

    private String principalName(Principal principal) {
        return principal == null ? "guest" : principal.getName();
    }

    private void removeUserSession(SessionWriter writer) {
        userSessions.computeIfPresent(writer.userName(), (userName, sessions) -> {
            sessions.remove(writer);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    private final class SessionWriter {

        private final WebSocketSession session;
        private final String userName;
        private final ConcurrentLinkedQueue<TextMessage> criticalQueue = new ConcurrentLinkedQueue<>();
        private final AtomicReference<TextMessage> latestPublicMessage = new AtomicReference<>();
        private final AtomicInteger criticalQueueSize = new AtomicInteger();
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicReference<CloseStatus> requestedClose = new AtomicReference<>();
        private volatile long subscribedWindowId;

        private SessionWriter(WebSocketSession session, String userName) {
            this.session = session;
            this.userName = userName;
        }

        private String userName() {
            return userName;
        }

        private void subscribe(long auctionWindowId) {
            subscribedWindowId = auctionWindowId;
        }

        private boolean isSubscribedTo(long auctionWindowId) {
            return subscribedWindowId == auctionWindowId;
        }

        private boolean offerCritical(TextMessage message) {
            if (message == null || !accepting.get()) {
                return false;
            }
            int newSize = criticalQueueSize.incrementAndGet();
            if (newSize > sessionQueueCapacity) {
                criticalQueueSize.decrementAndGet();
                performanceMetrics.recordWebSocketBackpressureClose();
                requestClose(BACKPRESSURE_CLOSE);
                return false;
            }
            criticalQueue.offer(message);
            scheduleDrain();
            return true;
        }

        private boolean offerPublic(TextMessage message) {
            if (message == null || !accepting.get()) {
                return false;
            }
            if (latestPublicMessage.getAndSet(message) != null) {
                performanceMetrics.recordPublicUpdateOverwrite();
            }
            scheduleDrain();
            return true;
        }

        private boolean offerTerminal(TextMessage message) {
            if (!accepting.get()) {
                return false;
            }
            latestPublicMessage.set(null);
            return offerCritical(message);
        }

        private void requestClose(CloseStatus status) {
            if (requestedClose.compareAndSet(null, status)) {
                accepting.set(false);
                clearPending();
                scheduleDrain();
            }
        }

        private void scheduleDrain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            rescheduleDrain();
        }

        private void drain() {
            boolean continueDrain = false;
            try {
                CloseStatus closeStatus = requestedClose.get();
                if (closeStatus != null) {
                    closeDirectly(closeStatus);
                    return;
                }
                for (int count = 0; count < WRITE_BATCH_SIZE; count++) {
                    TextMessage message = criticalQueue.poll();
                    if (message == null) {
                        break;
                    }
                    criticalQueueSize.decrementAndGet();
                    session.sendMessage(message);
                }
                TextMessage publicMessage = latestPublicMessage.getAndSet(null);
                if (publicMessage != null) {
                    session.sendMessage(publicMessage);
                }
                continueDrain = hasPending();
            } catch (IOException exception) {
                closeDirectly(SERVER_ERROR_CLOSE);
            } finally {
                if (continueDrain) {
                    rescheduleDrain();
                } else {
                    draining.set(false);
                    if ((requestedClose.get() != null || hasPending())
                            && draining.compareAndSet(false, true)) {
                        rescheduleDrain();
                    }
                }
            }
        }

        private boolean hasPending() {
            return !criticalQueue.isEmpty() || latestPublicMessage.get() != null;
        }

        private void rescheduleDrain() {
            try {
                outboundExecutor.execute(this::drain);
            } catch (RuntimeException exception) {
                draining.set(false);
                closeDirectly(SERVER_ERROR_CLOSE);
            }
        }

        private void closeDirectly(CloseStatus status) {
            accepting.set(false);
            requestedClose.set(null);
            clearPending();
            sessionWriters.remove(session.getId(), this);
            removeUserSession(this);
            if (session.isOpen()) {
                try {
                    session.close(status);
                } catch (IOException ignored) {
                    // 对端可能已关闭底层连接，本地状态仍需完成清理。
                }
            }
        }

        private void clearPending() {
            criticalQueue.clear();
            criticalQueueSize.set(0);
            latestPublicMessage.set(null);
        }

        private void dispose() {
            accepting.set(false);
            requestedClose.set(null);
            clearPending();
        }
    }
}
