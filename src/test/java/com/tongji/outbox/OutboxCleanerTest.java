package com.tongji.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxCleanerTest {
    private static final long MAX_CAUGHT_UP_AGE_MILLIS = 300_000L;
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-28T10:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void deletesOneBoundedBatchOnlyWhenCanalRecentlyCaughtUp() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        CanalKafkaBridge bridge = mock(CanalKafkaBridge.class);
        when(bridge.isCleanupSafe(CLOCK.millis(), MAX_CAUGHT_UP_AGE_MILLIS)).thenReturn(true);
        when(mapper.deleteCreatedBefore(any(LocalDateTime.class), anyInt())).thenReturn(7);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxCleaner cleaner = new OutboxCleaner(
                mapper,
                bridge,
                registry,
                true,
                720,
                1_000,
                MAX_CAUGHT_UP_AGE_MILLIS,
                CLOCK
        );

        cleaner.clean();

        verify(mapper).deleteCreatedBefore(
                LocalDateTime.of(2026, 7, 29, 18, 0),
                1_000
        );
        assertThat(registry.get("outbox.cleanup.runs").tag("result", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("outbox.cleanup.deleted.rows").counter().count()).isEqualTo(7);
    }

    @Test
    void keepsRowsWhenCanalCatchUpEvidenceIsUnavailable() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        CanalKafkaBridge bridge = mock(CanalKafkaBridge.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxCleaner cleaner = new OutboxCleaner(
                mapper,
                bridge,
                registry,
                true,
                720,
                1_000,
                MAX_CAUGHT_UP_AGE_MILLIS,
                CLOCK
        );

        cleaner.clean();

        verify(mapper, never()).deleteCreatedBefore(any(LocalDateTime.class), anyInt());
    }

    @Test
    void exposesDatabaseFailureToSchedulerAndMetrics() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        CanalKafkaBridge bridge = mock(CanalKafkaBridge.class);
        when(bridge.isCleanupSafe(CLOCK.millis(), MAX_CAUGHT_UP_AGE_MILLIS)).thenReturn(true);
        when(mapper.deleteCreatedBefore(any(LocalDateTime.class), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxCleaner cleaner = new OutboxCleaner(
                mapper,
                bridge,
                registry,
                true,
                720,
                1_000,
                MAX_CAUGHT_UP_AGE_MILLIS,
                CLOCK
        );

        assertThatThrownBy(cleaner::clean)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared outbox cleanup failed")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(registry.get("outbox.cleanup.runs").tag("result", "failure").counter().count())
                .isEqualTo(1);
    }
}
