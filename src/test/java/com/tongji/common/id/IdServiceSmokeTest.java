package com.tongji.common.id;

import com.tongji.common.id.segment.SegmentAllocator;
import com.tongji.common.id.segment.SegmentIdGenerator;
import com.tongji.common.id.segment.SegmentIdProperties;
import com.tongji.common.id.segment.SegmentRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class IdServiceSmokeTest {

    @Test
    void snowflakeNamespaceGeneratesFiftyThousandPositiveUniqueIds() {
        DefaultIdService idService = new DefaultIdService(new SnowflakeIdGenerator(1, 1));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Set<Long> ids = new HashSet<>(65_536);

            for (int i = 0; i < 50_000; i++) {
                long id = idService.nextId(IdNamespace.POST);
                assertThat(id).isPositive();
                ids.add(id);
            }

            assertThat(ids).hasSize(50_000);
        });
    }

    @Test
    void segmentNamespaceGeneratesTenThousandPositiveUniqueIdsThroughDefaultIdService() {
        RecordingSegmentAllocator allocator = new RecordingSegmentAllocator(1_024L);
        SegmentIdProperties properties = new SegmentIdProperties();
        properties.setWaitTimeoutMs(50L);
        DefaultIdService idService = new DefaultIdService(
                new SnowflakeIdGenerator(1, 1),
                new SegmentIdGenerator(allocator, properties, Runnable::run)
        );

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Set<Long> ids = new HashSet<>(16_384);

            for (int i = 0; i < 10_000; i++) {
                long id = idService.nextId(IdNamespace.RECONCILIATION_TASK);
                assertThat(id).isPositive();
                ids.add(id);
            }

            assertThat(ids).hasSize(10_000);
            assertThat(allocator.bizTags()).isNotEmpty().allMatch("reconciliation_task"::equals);
            assertThat(allocator.allocationCount()).isGreaterThan(1);
        });
    }

    private static final class RecordingSegmentAllocator extends SegmentAllocator {
        private final long step;
        private final List<String> bizTags = new ArrayList<>();
        private long nextStart = 1L;

        private RecordingSegmentAllocator(long step) {
            super(null);
            this.step = step;
        }

        @Override
        public synchronized SegmentRange allocateSegment(String bizTag) {
            bizTags.add(bizTag);

            long startInclusive = nextStart;
            long endInclusive = startInclusive + step - 1L;
            nextStart = endInclusive + 1L;

            return new SegmentRange(startInclusive, endInclusive);
        }

        private synchronized List<String> bizTags() {
            return List.copyOf(bizTags);
        }

        private synchronized int allocationCount() {
            return bizTags.size();
        }
    }
}
