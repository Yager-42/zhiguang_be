package com.tongji.comment.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMetricsTest {

    @Test
    void recordsOnlyBoundedTagsAndDurations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommentMetrics metrics = new CommentMetrics(registry);

        metrics.cache("l1", "hit");
        metrics.outbox("published", "COMMENT_CREATED", 2);
        metrics.materialization("cassandra", "success", Duration.ofMillis(5));
        metrics.sideEffect("reward", "success");
        metrics.dlt("update_failure");

        assertThat(registry.counter("comment.cache.requests", "level", "l1", "result", "hit").count())
                .isEqualTo(1);
        assertThat(registry.counter("comment.outbox.events", "result", "published",
                "event_type", "COMMENT_CREATED").count()).isEqualTo(2);
        assertThat(registry.timer("comment.materialization.duration", "stage", "cassandra",
                "result", "success").count()).isEqualTo(1);
        assertThat(registry.counter("comment.side_effect.events", "effect", "reward",
                "result", "success").count()).isEqualTo(1);
        assertThat(registry.counter("comment.materialization.dlt", "result", "update_failure").count())
                .isEqualTo(1);
    }
}
