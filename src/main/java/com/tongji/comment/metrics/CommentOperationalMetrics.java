package com.tongji.comment.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches low-frequency database backlog snapshots for metrics scrapes.
 *
 * <p>The scheduled sampler owns the database queries, so scraping metrics never scans hot tables.</p>
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
    private final AtomicLong outboxReadyCount = new AtomicLong();
    private final AtomicLong outboxClaimedCount = new AtomicLong();
    private final AtomicLong outboxOldestReadySeconds = new AtomicLong();
    private final AtomicLong nextWarningNanos = new AtomicLong();

    public CommentOperationalMetrics(JdbcTemplate jdbcTemplate,
                                     MeterRegistry registry,
                                     CommentMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        registry.gauge("comment.pending.count", pendingCount);
        registry.gauge("comment.pending.oldest.seconds", pendingOldestSeconds);
        registry.gauge("comment.outbox.ready.count", outboxReadyCount);
        registry.gauge("comment.outbox.claimed.count", outboxClaimedCount);
        registry.gauge("comment.outbox.oldest.ready.seconds", outboxOldestReadySeconds);
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
            OutboxSnapshot outbox = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(state = 0), 0),
                           COALESCE(SUM(state = 1), 0),
                           COALESCE(TIMESTAMPDIFF(
                               SECOND, MIN(CASE WHEN state = 0 THEN created_at END), NOW()), 0)
                    FROM comment_outbox
                    WHERE state IN (0, 1)
                    """, (resultSet, rowNumber) -> new OutboxSnapshot(
                    resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3)));
            if (pending != null && outbox != null) {
                pendingCount.set(pending.count());
                pendingOldestSeconds.set(nonNegative(pending.oldestSeconds()));
                outboxReadyCount.set(outbox.readyCount());
                outboxClaimedCount.set(outbox.claimedCount());
                outboxOldestReadySeconds.set(nonNegative(outbox.oldestReadySeconds()));
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

    private record OutboxSnapshot(long readyCount, long claimedCount, long oldestReadySeconds) {
    }
}
