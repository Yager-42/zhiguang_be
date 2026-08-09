package com.tongji.promotion.bprime.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Native high-activity bid transport with one bounded, serialized writer pump per connection. */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionNativeBidWebSocketHandler extends TextWebSocketHandler {

    private static final CloseStatus BACKPRESSURE_CLOSE = new CloseStatus(4000, "backpressure");
    private static final CloseStatus SERVER_ERROR_CLOSE = new CloseStatus(1011, "server_error");
    private static final int WRITE_BATCH_SIZE = 64;

    private final PromotionBidWebSocketProtocolService protocolService;
    private final ObjectReader requestReader;
    private final ObjectWriter ackWriter;
    private final TaskExecutor outboundExecutor;
    private final int sessionQueueCapacity;
    private final Map<String, SessionWriter> sessionWriters = new ConcurrentHashMap<>();

    public PromotionNativeBidWebSocketHandler(
            PromotionBidWebSocketProtocolService protocolService,
            ObjectMapper objectMapper,
            @Qualifier("promotionBidWebSocketOutboundExecutor") TaskExecutor outboundExecutor,
            PromotionBPrimeProperties properties) {
        this.protocolService = protocolService;
        this.requestReader = objectMapper.readerFor(PromotionWebSocketBidRequest.class);
        this.ackWriter = objectMapper.writerFor(PromotionWebSocketBidAck.class);
        this.outboundExecutor = outboundExecutor;
        this.sessionQueueCapacity = properties.getWebSocketNativeSessionQueueCapacity();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionWriters.put(session.getId(), new SessionWriter(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionWriter writer = sessionWriters.get(session.getId());
        if (writer == null) {
            return;
        }
        final PromotionWebSocketBidRequest request;
        try {
            request = requestReader.readValue(message.getPayload());
        } catch (IOException exception) {
            writer.offer(protocolService.rejected(null, ErrorCode.BAD_REQUEST.getCode()));
            return;
        }
        protocolService.submit(request, session.getPrincipal())
                .whenComplete((ack, exception) -> {
                    if (exception == null) {
                        writer.offer(ack);
                    } else {
                        writer.requestClose(SERVER_ERROR_CLOSE);
                    }
                });
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

    private final class SessionWriter {

        private final WebSocketSession session;
        private final ConcurrentLinkedQueue<PromotionWebSocketBidAck> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queueSize = new AtomicInteger();
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicReference<CloseStatus> requestedClose = new AtomicReference<>();

        private SessionWriter(WebSocketSession session) {
            this.session = session;
        }

        private void offer(PromotionWebSocketBidAck ack) {
            if (!accepting.get()) {
                return;
            }
            int newSize = queueSize.incrementAndGet();
            if (newSize > sessionQueueCapacity) {
                queueSize.decrementAndGet();
                requestClose(BACKPRESSURE_CLOSE);
                return;
            }
            queue.offer(ack);
            scheduleDrain();
        }

        private void requestClose(CloseStatus status) {
            if (requestedClose.compareAndSet(null, status)) {
                accepting.set(false);
                queue.clear();
                queueSize.set(0);
                scheduleDrain();
            }
        }

        private void scheduleDrain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                outboundExecutor.execute(this::drain);
            } catch (RuntimeException exception) {
                draining.set(false);
                closeDirectly(SERVER_ERROR_CLOSE);
            }
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
                    PromotionWebSocketBidAck ack = queue.poll();
                    if (ack == null) {
                        break;
                    }
                    queueSize.decrementAndGet();
                    session.sendMessage(new TextMessage(ackWriter.writeValueAsString(ack)));
                }
                continueDrain = !queue.isEmpty();
            } catch (IOException exception) {
                closeDirectly(SERVER_ERROR_CLOSE);
            } finally {
                if (continueDrain) {
                    rescheduleDrain();
                } else {
                    draining.set(false);
                    if ((requestedClose.get() != null || !queue.isEmpty())
                            && draining.compareAndSet(false, true)) {
                        rescheduleDrain();
                    }
                }
            }
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
            queue.clear();
            queueSize.set(0);
            if (session.isOpen()) {
                try {
                    session.close(status);
                } catch (IOException ignored) {
                    // The peer may already have closed the underlying socket.
                }
            }
        }

        private void dispose() {
            accepting.set(false);
            requestedClose.set(null);
            queue.clear();
            queueSize.set(0);
        }
    }
}
