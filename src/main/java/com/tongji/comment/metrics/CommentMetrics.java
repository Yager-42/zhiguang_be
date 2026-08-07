package com.tongji.comment.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 评论吞吐链路的低基数指标入口。
 */
@Component
public class CommentMetrics {
    private final MeterRegistry registry;

    public CommentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void cache(String level, String result) {
        registry.counter("comment.cache.requests", "level", level, "result", result).increment();
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void readDependency(String dependency, String result) {
        registry.counter("comment.read.dependencies", "dependency", dependency, "result", result).increment();
    }

    public void l3(Timer.Sample sample, String result) {
        sample.stop(registry.timer("comment.cache.l3.duration", "result", result));
    }

    public void outbox(String result, String eventType, long count) {
        registry.counter("comment.outbox.events", "result", result, "event_type", eventType).increment(count);
    }

    public void outboxBatch(int size, Duration duration) {
        registry.summary("comment.outbox.batch.size").record(size);
        registry.timer("comment.outbox.batch.duration").record(duration);
    }

    public void materialization(String stage, String result, Duration duration) {
        registry.timer("comment.materialization.duration", "stage", stage, "result", result).record(duration);
    }

    public void materialized(String result) {
        registry.counter("comment.materialization.events", "result", result).increment();
    }

    public void acceptedToSucceeded(Duration duration) {
        if (duration.isNegative()) {
            registry.counter("comment.materialization.clock_skew").increment();
            return;
        }
        registry.timer("comment.pending.accepted_to_succeeded").record(duration);
    }

    public void sideEffect(String effect, String result) {
        registry.counter("comment.side_effect.events", "effect", effect, "result", result).increment();
    }

    public void dlt(String result) {
        registry.counter("comment.materialization.dlt", "result", result).increment();
    }

    public void operationalSample(String result) {
        registry.counter("comment.operational.samples", "result", result).increment();
    }
}
