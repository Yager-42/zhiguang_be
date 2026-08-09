package com.tongji.promotion.bprime.metrics;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records promotion throughput at durable stage boundaries without high-cardinality identifiers.
 *
 * @since 2026-08-08
 */
@Component
public class PromotionPerformanceMetrics {

    private final MeterRegistry registry;
    private final Counter ingressAccepted;
    private final Counter fastRejectedBidNotHigher;
    private final Counter fastRejectPrecheckUnavailable;
    private final Counter webSocketAckRejected;
    private final Counter webSocketAckPublished;
    private final Timer decisionLatency;
    private final Timer projectionEndToEndLatency;
    private final Timer realtimeEndToEndLatency;
    private final AtomicInteger publisherQueueDepth = new AtomicInteger();
    private final AtomicInteger publisherActiveWorkers = new AtomicInteger();

    public PromotionPerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressAccepted = registry.counter("promotion.bprime.ingress", "result", "accepted");
        this.fastRejectedBidNotHigher = registry.counter("promotion.bprime.ingress", "result", "fast_rejected",
                "reason", "bid_not_higher");
        this.fastRejectPrecheckUnavailable = registry.counter("promotion.bprime.fast.reject.precheck",
                "result", "unavailable");
        this.webSocketAckRejected = registry.counter("promotion.bprime.websocket.bid.ack", "status", "rejected");
        this.webSocketAckPublished = registry.counter("promotion.bprime.websocket.bid.ack", "status", "published");
        this.decisionLatency = registry.timer("promotion.bprime.decision.latency");
        this.projectionEndToEndLatency = registry.timer("promotion.bprime.end.to.end", "target", "projection");
        this.realtimeEndToEndLatency = registry.timer("promotion.bprime.end.to.end", "target", "websocket");
        Gauge.builder("promotion.bprime.publisher.queue.depth", publisherQueueDepth, AtomicInteger::get)
                .register(registry);
        Gauge.builder("promotion.bprime.publisher.active.workers", publisherActiveWorkers, AtomicInteger::get)
                .register(registry);
    }

    /** Records one command accepted by the RocketMQ broker. */
    public void recordIngressAccepted() {
        ingressAccepted.increment();
    }

    /** Records one request rejected by the monotonic in-process gateway filter. */
    public void recordFastRejected(String reason) {
        if ("BID_NOT_HIGHER".equals(reason)) {
            fastRejectedBidNotHigher.increment();
            return;
        }
        registry.counter("promotion.bprime.ingress", "result", "fast_rejected", "reason",
                reason.toLowerCase(Locale.ROOT)).increment();
    }

    /** Records an inconclusive Redis guard read that deliberately fell through to the authoritative path. */
    public void recordFastRejectPrecheckFailure() {
        fastRejectPrecheckUnavailable.increment();
    }

    /** Records one private WebSocket bid acknowledgment. */
    public void recordWebSocketBidAck(String status) {
        if ("REJECTED".equals(status)) {
            webSocketAckRejected.increment();
        } else if ("PUBLISHED".equals(status)) {
            webSocketAckPublished.increment();
        } else {
            registry.counter("promotion.bprime.websocket.bid.ack", "status",
                    status.toLowerCase(Locale.ROOT)).increment();
        }
    }

    /** Records one Redis decision after Kafka acknowledged the durable append. */
    public void recordDecisionDurable(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.decision", "result", result(decision)).increment();
        recordElapsed(decisionLatency, submittedAt(decision));
    }

    /** Records one decision after its MySQL projection transaction committed. */
    public void recordProjectionComplete(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.projection", "result", result(decision)).increment();
        recordElapsed(projectionEndToEndLatency, submittedAt(decision));
    }

    /** Records one decision after the realtime publisher returned successfully. */
    public void recordRealtimeComplete(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.realtime", "result", result(decision)).increment();
        recordElapsed(realtimeEndToEndLatency, submittedAt(decision));
    }

    public void updatePublisherState(int queueDepth, int activeWorkers) {
        publisherQueueDepth.set(Math.max(0, queueDepth));
        publisherActiveWorkers.set(Math.max(0, activeWorkers));
    }

    private String result(PromotionAuctionDecision decision) {
        if ("WINDOW_CLOSED".equals(decision.decisionType())) {
            return "window_closed";
        }
        return decision.accepted() ? "accepted" : "rejected";
    }

    private Instant submittedAt(PromotionAuctionDecision decision) {
        Object value = decision.payload().get("submittedAt");
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (RuntimeException ignored) {
                registry.counter("promotion.bprime.metrics.invalid.submitted.at",
                        "decision_type", decision.decisionType().toLowerCase(Locale.ROOT)).increment();
            }
        }
        return null;
    }

    private void recordElapsed(Timer timer, Instant start) {
        if (start == null) {
            return;
        }
        Duration elapsed = Duration.between(start, Instant.now());
        if (!elapsed.isNegative()) {
            timer.record(elapsed);
        }
    }
}
