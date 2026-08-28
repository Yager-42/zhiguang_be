package com.tongji.comment.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存低频评论 pending 快照，使指标抓取不扫描热表。
 *
 * @since 2026-08-07
 */
@Slf4j
@Component
public class CommentOperationalMetrics {
    private static final long WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private final JdbcTemplate jdbcTemplate;
    private final CommentMetrics metrics;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong pendingOldestSeconds = new AtomicLong();
    private final AtomicLong nextWarningNanos = new AtomicLong();

    public CommentOperationalMetrics(JdbcTemplate jdbcTemplate,
                                     MeterRegistry registry,
                                     CommentMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        registry.gauge("comment.pending.count", pendingCount);
        registry.gauge("comment.pending.oldest.seconds", pendingOldestSeconds);
    }

    @Scheduled(fixedDelayString = "${comment.metrics.sample-interval-ms:5000}")
    public void sample() {
        try {
            PendingSnapshot pending = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*),
                           COALESCE(TIMESTAMPDIFF(SECOND, MIN(create_time), NOW()), 0)
                    FROM pending_comments
                    WHERE status = 'pending'
                    """, (resultSet, rowNumber) -> new PendingSnapshot(
                    resultSet.getLong(1), resultSet.getLong(2)));
            if (pending != null) {
                pendingCount.set(pending.count());
                pendingOldestSeconds.set(nonNegative(pending.oldestSeconds()));
                metrics.operationalSample("success");
            }
        } catch (RuntimeException exception) {
            metrics.operationalSample("failure");
            warnRateLimited(exception);
        }
    }

    private void warnRateLimited(RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextWarningNanos.get();
        if (now >= next && nextWarningNanos.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            log.warn("comment operational metric sample failed", exception);
        }
    }

    private long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private record PendingSnapshot(long count, long oldestSeconds) {
    }

}
