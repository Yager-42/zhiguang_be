package com.tongji.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 在单活 Canal Bridge 已确认追平时，有界清理超过保留窗口的共享 Outbox 行。
 *
 * <p>Canal 停用、断连、存在积压或追平证据过期时不删除；删除事件本身由 Bridge 忽略。</p>
 *
 * @since 2026-08-28
 */
@Slf4j
@Component
public class OutboxCleaner {
    private final OutboxMapper outboxMapper;
    private final CanalKafkaBridge canalKafkaBridge;
    private final boolean enabled;
    private final int retentionHours;
    private final int batchSize;
    private final long maxCaughtUpAgeMillis;
    private final Clock clock;
    private final Counter cleanupSuccess;
    private final Counter cleanupFailure;
    private final Counter deletedRows;

    @Autowired
    public OutboxCleaner(OutboxMapper outboxMapper,
                         CanalKafkaBridge canalKafkaBridge,
                         MeterRegistry meterRegistry,
                         @Value("${outbox.cleanup.enabled:true}") boolean enabled,
                         @Value("${outbox.cleanup.retention-hours:720}") int retentionHours,
                         @Value("${outbox.cleanup.batch-size:1000}") int batchSize,
                         @Value("${outbox.cleanup.max-caught-up-age-ms:300000}") long maxCaughtUpAgeMillis) {
        this(outboxMapper, canalKafkaBridge, meterRegistry, enabled, retentionHours, batchSize,
                maxCaughtUpAgeMillis, Clock.systemDefaultZone());
    }

    OutboxCleaner(OutboxMapper outboxMapper,
                  CanalKafkaBridge canalKafkaBridge,
                  MeterRegistry meterRegistry,
                  boolean enabled,
                  int retentionHours,
                  int batchSize,
                  long maxCaughtUpAgeMillis,
                  Clock clock) {
        if (retentionHours <= 0 || batchSize <= 0 || maxCaughtUpAgeMillis <= 0L) {
            throw new IllegalArgumentException("Outbox cleanup configuration must be positive");
        }
        this.outboxMapper = outboxMapper;
        this.canalKafkaBridge = canalKafkaBridge;
        this.enabled = enabled;
        this.retentionHours = retentionHours;
        this.batchSize = batchSize;
        this.maxCaughtUpAgeMillis = maxCaughtUpAgeMillis;
        this.clock = clock;
        this.cleanupSuccess = meterRegistry.counter("outbox.cleanup.runs", "result", "success");
        this.cleanupFailure = meterRegistry.counter("outbox.cleanup.runs", "result", "failure");
        this.deletedRows = meterRegistry.counter("outbox.cleanup.deleted.rows");
    }

    /**
     * 删除一批已超过保留窗口的事件；不满足 Canal 安全门槛时无操作。
     */
    @Scheduled(fixedDelayString = "${outbox.cleanup.interval-ms:3600000}")
    public void clean() {
        if (!enabled || !canalKafkaBridge.isCleanupSafe(clock.millis(), maxCaughtUpAgeMillis)) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(retentionHours);
        try {
            int deleted = outboxMapper.deleteCreatedBefore(cutoff, batchSize);
            cleanupSuccess.increment();
            deletedRows.increment(deleted);
            if (deleted > 0) {
                log.info("shared outbox cleanup deleted {} rows before {}", deleted, cutoff);
            }
        } catch (RuntimeException exception) {
            cleanupFailure.increment();
            throw new IllegalStateException(
                    "shared outbox cleanup failed, cutoff=" + cutoff + ", batchSize=" + batchSize,
                    exception);
        }
    }
}
