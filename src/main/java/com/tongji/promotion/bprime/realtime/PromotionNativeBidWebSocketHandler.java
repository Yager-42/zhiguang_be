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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 高活动竞价原生传输：每连接使用关键反馈优先、公共状态可覆盖的单写泵。
 *
 * <p>最终 ACK 进入有界 FIFO；房间公共状态只保留最新批次。关键队列过载会关闭连接，客户端可凭
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
    private final ObjectWriter publicEventWriter;
    private final ObjectWriter subscriptionAckWriter;
    private final TaskExecutor outboundExecutor;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final int sessionQueueCapacity;
    private final Map<String, SessionWriter> sessionWriters = new ConcurrentHashMap<>();
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();

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
        this.publicEventWriter = objectMapper.writerFor(PromotionAuctionRealtimeEvent.class);
        this.subscriptionAckWriter = objectMapper.writerFor(PromotionNativeSubscriptionAck.class);
        this.outboundExecutor = outboundExecutor;
        this.performanceMetrics = performanceMetrics;
        this.sessionQueueCapacity = properties.getWebSocketNativeSessionQueueCapacity();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionWriter writer = new SessionWriter(session);
        SessionWriter previous = sessionWriters.put(session.getId(), writer);
        if (previous != null) {
            previous.dispose();
        }
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
        Room room = rooms.get(event.auctionWindowId());
        if (room == null) {
            return 0;
        }
        boolean terminal = PromotionAuctionRealtimeEvent.WINDOW_CLOSED.equals(event.eventType());
        int delivered = 0;
        for (SessionWriter writer : room.writers) {
            boolean accepted = terminal
                    ? writer.offerTerminal(message, room)
                    : writer.offerPublic(message, room);
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
        if (writer.subscribe(request.auctionWindowId())) {
            writer.offerCritical(serialize(subscriptionAckWriter, new PromotionNativeSubscriptionAck(
                    PromotionNativeSubscriptionAck.SUBSCRIBED,
                    String.valueOf(request.auctionWindowId())), writer));
        }
    }

    public int subscriberCount(long auctionWindowId) {
        Room room = rooms.get(auctionWindowId);
        return room == null ? 0 : room.writers.size();
    }

    public int pendingPublicMessageCount(long auctionWindowId) {
        Room room = rooms.get(auctionWindowId);
        return room == null ? 0 : room.pendingPublicMessageCount.get();
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

    private final class SessionWriter {

        private final WebSocketSession session;
        private final ConcurrentLinkedQueue<TextMessage> criticalQueue = new ConcurrentLinkedQueue<>();
        private final AtomicReference<PublicMessage> latestPublicMessage = new AtomicReference<>();
        private final AtomicInteger criticalQueueSize = new AtomicInteger();
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicReference<CloseStatus> requestedClose = new AtomicReference<>();
        private volatile Room subscribedRoom;

        private SessionWriter(WebSocketSession session) {
            this.session = session;
        }

        private synchronized boolean subscribe(long auctionWindowId) {
            if (!accepting.get()) {
                return false;
            }
            Room current = subscribedRoom;
            if (current != null && current.auctionWindowId == auctionWindowId) {
                return true;
            }
            clearPublicMessage();
            if (current != null) {
                removeFromRoom(current);
            }
            rooms.compute(auctionWindowId, (ignored, indexedRoom) -> {
                Room room = indexedRoom == null ? new Room(auctionWindowId) : indexedRoom;
                subscribedRoom = room;
                room.writers.add(this);
                return room;
            });
            return true;
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

        private synchronized boolean offerPublic(TextMessage message, Room room) {
            if (message == null || !accepting.get() || subscribedRoom != room) {
                return false;
            }
            PublicMessage previous = latestPublicMessage.getAndSet(new PublicMessage(room, message));
            if (previous == null) {
                room.pendingPublicMessageCount.incrementAndGet();
            } else {
                performanceMetrics.recordPublicUpdateOverwrite();
            }
            scheduleDrain();
            return true;
        }

        private synchronized boolean offerTerminal(TextMessage message, Room room) {
            if (!accepting.get() || subscribedRoom != room) {
                return false;
            }
            clearPublicMessage();
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
                PublicMessage publicMessage = latestPublicMessage.getAndSet(null);
                if (publicMessage != null) {
                    publicMessage.room.pendingPublicMessageCount.decrementAndGet();
                    session.sendMessage(publicMessage.message);
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
            leaveRoom();
            sessionWriters.remove(session.getId(), this);
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
            clearPublicMessage();
        }

        private void clearPublicMessage() {
            PublicMessage publicMessage = latestPublicMessage.getAndSet(null);
            if (publicMessage != null) {
                publicMessage.room.pendingPublicMessageCount.decrementAndGet();
            }
        }

        private synchronized void leaveRoom() {
            Room current = subscribedRoom;
            subscribedRoom = null;
            if (current != null) {
                removeFromRoom(current);
            }
        }

        private void removeFromRoom(Room room) {
            rooms.computeIfPresent(room.auctionWindowId, (ignored, indexedRoom) -> {
                if (indexedRoom != room) {
                    return indexedRoom;
                }
                indexedRoom.writers.remove(this);
                return indexedRoom.writers.isEmpty() ? null : indexedRoom;
            });
        }

        private void dispose() {
            accepting.set(false);
            requestedClose.set(null);
            clearPending();
            leaveRoom();
        }
    }

    private final class Room {

        private final long auctionWindowId;
        private final java.util.Set<SessionWriter> writers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger pendingPublicMessageCount = new AtomicInteger();

        private Room(long auctionWindowId) {
            this.auctionWindowId = auctionWindowId;
        }
    }

    private final class PublicMessage {

        private final Room room;
        private final TextMessage message;

        private PublicMessage(Room room, TextMessage message) {
            this.room = room;
            this.message = message;
        }
    }
}
