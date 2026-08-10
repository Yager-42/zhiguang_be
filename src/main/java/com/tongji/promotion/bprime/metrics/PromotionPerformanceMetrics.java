package com.tongji.promotion.bprime.metrics;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
    private final Counter webSocketAckRejected;
    private final Counter publicUpdateBatches;
    private final Counter publicUpdateDeltas;
    private final Counter publicUpdateOverwrites;
    private final Counter webSocketBackpressureCloses;
    private final Counter streamTrimmedEvents;
    private final DistributionSummary streamLength;
    private final DistributionSummary streamLag;
    private final Timer decisionLatency;
    private final Timer streamDrainDuration;
    private final Timer projectionEndToEndLatency;
    private final Timer realtimeEndToEndLatency;
    private final AtomicInteger publisherQueueDepth = new AtomicInteger();
    private final AtomicInteger publisherActiveWorkers = new AtomicInteger();

    public PromotionPerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressAccepted = registry.counter("promotion.bprime.ingress", "result", "accepted");
        this.webSocketAckRejected = registry.counter("promotion.bprime.websocket.bid.ack", "status", "rejected");
        this.publicUpdateBatches = registry.counter("promotion.bprime.websocket.public.update", "result", "batch");
        this.publicUpdateDeltas = registry.counter("promotion.bprime.websocket.public.update", "result", "delta");
        this.publicUpdateOverwrites = registry.counter(
                "promotion.bprime.websocket.public.update", "result", "overwritten");
        this.webSocketBackpressureCloses = registry.counter(
                "promotion.bprime.websocket.connection.close", "reason", "backpressure");
        this.streamTrimmedEvents = registry.counter("promotion.bprime.stream.trimmed.events");
        this.streamLength = registry.summary("promotion.bprime.stream.length");
        this.streamLag = registry.summary("promotion.bprime.stream.lag");
        this.decisionLatency = registry.timer("promotion.bprime.decision.latency");
        this.streamDrainDuration = registry.timer("promotion.bprime.stream.drain.duration");
        this.projectionEndToEndLatency = registry.timer("promotion.bprime.end.to.end", "target", "projection");
        this.realtimeEndToEndLatency = registry.timer("promotion.bprime.end.to.end", "target", "websocket");
        Gauge.builder("promotion.bprime.publisher.queue.depth", publisherQueueDepth, AtomicInteger::get)
                .register(registry);
        Gauge.builder("promotion.bprime.publisher.active.workers", publisherActiveWorkers, AtomicInteger::get)
                .register(registry);
    }

    /** 记录一次 Redis Lua 最终裁决。 */
    public void recordIngressAccepted() {
        ingressAccepted.increment();
    }

    /** Records one private WebSocket bid acknowledgment. */
    public void recordWebSocketBidAck(String status) {
        if ("REJECTED".equals(status)) {
            webSocketAckRejected.increment();
        } else {
            registry.counter("promotion.bprime.websocket.bid.ack", "status",
                    status.toLowerCase(Locale.ROOT)).increment();
        }
    }

    /** 记录 Redis Lua 已写入 Stream 的决策。 */
    public void recordDecisionDurable(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.decision", "result", result(decision)).increment();
        recordElapsed(decisionLatency, submittedAt(decision));
    }

    /** Records one decision after its MySQL projection transaction committed. */
    public void recordProjectionComplete(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.projection", "result", result(decision)).increment();
        recordElapsed(projectionEndToEndLatency, submittedAt(decision));
    }

    /** Records one decision after the realtime delivery layer accepted it, not after a client received it. */
    public void recordRealtimeComplete(PromotionAuctionDecision decision) {
        registry.counter("promotion.bprime.realtime", "result", result(decision)).increment();
        recordElapsed(realtimeEndToEndLatency, submittedAt(decision));
    }

    public void updatePublisherState(int queueDepth, int activeWorkers) {
        publisherQueueDepth.set(Math.max(0, queueDepth));
        publisherActiveWorkers.set(Math.max(0, activeWorkers));
    }

    /** Records one coalesced public update and the number of campaign deltas it contains. */
    public void recordPublicUpdateBatch(int deltaCount) {
        publicUpdateBatches.increment();
        publicUpdateDeltas.increment(Math.max(0, deltaCount));
    }

    /** Records replacement of an unsent recoverable public update for a slow native connection. */
    public void recordPublicUpdateOverwrite() {
        publicUpdateOverwrites.increment();
    }

    /** Records a native connection closed because its critical feedback queue reached capacity. */
    public void recordWebSocketBackpressureClose() {
        webSocketBackpressureCloses.increment();
    }

    /** 记录 checkpoint 安全裁剪的 Stream 事件数。 */
    public void recordStreamTrimmed(long count) {
        streamTrimmedEvents.increment(Math.max(0L, count));
    }

    /** 记录 Stream 保留长度及其相对 MySQL checkpoint 的版本差。 */
    public void recordStreamState(long length, long lag) {
        streamLength.record(Math.max(0L, length));
        streamLag.record(Math.max(0L, lag));
    }

    /** 记录一次窗口 Stream 追平循环的耗时。 */
    public void recordStreamDrain(long elapsedNanos) {
        streamDrainDuration.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
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
