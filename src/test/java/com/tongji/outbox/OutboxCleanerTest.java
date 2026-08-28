package com.tongji.outbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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
        OutboxCleaner cleaner = new OutboxCleaner(
                mapper,
                bridge,
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
    }

    @Test
    void keepsRowsWhenCanalCatchUpEvidenceIsUnavailable() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        CanalKafkaBridge bridge = mock(CanalKafkaBridge.class);
        OutboxCleaner cleaner = new OutboxCleaner(
                mapper,
                bridge,
                true,
                720,
                1_000,
                MAX_CAUGHT_UP_AGE_MILLIS,
                CLOCK
        );

        cleaner.clean();

        verify(mapper, never()).deleteCreatedBefore(any(LocalDateTime.class), anyInt());
    }
}
