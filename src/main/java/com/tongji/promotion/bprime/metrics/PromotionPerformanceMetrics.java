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
    private final Counter ingressFastRejected;
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
    private final Timer bidBatchDuration;
    private final DistributionSummary bidBatchCommands;
    private final Counter bidBackpressure;
    private final AtomicInteger publisherQueueDepth = new AtomicInteger();
    private final AtomicInteger publisherActiveWorkers = new AtomicInteger();
    private final AtomicInteger bidPendingDepth = new AtomicInteger();
    private final AtomicInteger bidActiveWindows = new AtomicInteger();

    public PromotionPerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressAccepted = registry.counter("promotion.bprime.ingress", "result", "accepted");
        this.webSocketAckRejected = registry.counter("promotion.bprime.websocket.bid.ack", "status", "rejected");
        this.ingressFastRejected = registry.counter("promotion.bprime.ingress", "result", "fast-rejected");
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
        this.bidBatchDuration = registry.timer("promotion.bprime.bid.batch.duration");
        this.bidBatchCommands = registry.summary("promotion.bprime.bid.batch.commands");
        this.bidBackpressure = registry.counter("promotion.bprime.bid.backpressure");
        Gauge.builder("promotion.bprime.publisher.queue.depth", publisherQueueDepth, AtomicInteger::get)
                .register(registry);
        Gauge.builder("promotion.bprime.publisher.active.workers", publisherActiveWorkers, AtomicInteger::get)
                .register(registry);
        Gauge.builder("promotion.bprime.bid.pending.depth", bidPendingDepth, AtomicInteger::get)
                .register(registry);
        Gauge.builder("promotion.bprime.bid.active.windows", bidActiveWindows, AtomicInteger::get)
                .register(registry);
    }

    /** 记录一次 Redis Lua 最终裁决。 */
    public void recordIngressAccepted() {
        ingressAccepted.increment();
    }

    /** 记录一次网关本地预拒（未进入 Lua 裁决）。 */
    public void recordFastRejected() {
        ingressFastRejected.increment();
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

    /** 记录一次批量 Redis 裁决及其命令数。 */
    public void recordBidBatch(int commandCount, long elapsedNanos) {
        bidBatchCommands.record(Math.max(0, commandCount));
        bidBatchDuration.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    /** 更新 flat combiner 的当前积压与活跃窗口数。 */
    public void updateBidCombinerState(int pendingDepth, int activeWindows) {
        bidPendingDepth.set(Math.max(0, pendingDepth));
        bidActiveWindows.set(Math.max(0, activeWindows));
    }

    /** 记录一次本地容量背压。 */
    public void recordBidBackpressure() {
        bidBackpressure.increment();
    }

    private String result(PromotionAuctionDecision decision) {
        String type = decision.decisionType();
        if ("AUCTION_SOLD".equals(type) || "AUCTION_NO_BID".equals(type)) {
            return "terminal";
        }
        if ("AUCTION_EXTENDED".equals(type)) {
            return "extended";
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
