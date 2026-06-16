package com.tongji.common.id;

import com.tongji.common.id.segment.SegmentIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdServiceSegmentRoutingTest {

    @Test
    void routesSegmentNamespacesToExpectedBizTags() {
        RecordingSnowflakeIdGenerator snowflakeIdGenerator = new RecordingSnowflakeIdGenerator(500L);
        RecordingSegmentIdGenerator segmentIdGenerator = new RecordingSegmentIdGenerator(1000L);
        DefaultIdService idService = new DefaultIdService(snowflakeIdGenerator, segmentIdGenerator);

        assertThat(idService.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(1001L);
        assertThat(idService.nextId(IdNamespace.ADMIN_OPERATION)).isEqualTo(1002L);
        assertThat(idService.nextId(IdNamespace.AUDIT_LOG)).isEqualTo(1003L);

        assertThat(segmentIdGenerator.bizTags())
                .containsExactly("reconciliation_task", "admin_operation", "audit_log");
        assertThat(snowflakeIdGenerator.invocations()).isZero();
    }

    private static final class RecordingSnowflakeIdGenerator extends SnowflakeIdGenerator {
        private long current;
        private long invocations;

        private RecordingSnowflakeIdGenerator(long seed) {
            super(1, 1);
            this.current = seed;
        }

        @Override
        public synchronized long nextId() {
            invocations++;
            return ++current;
        }

        private long invocations() {
            return invocations;
        }
    }

    private static final class RecordingSegmentIdGenerator extends SegmentIdGenerator {
        private final List<String> bizTags = new ArrayList<>();
        private long current;

        private RecordingSegmentIdGenerator(long seed) {
            super(null, null, Runnable::run);
            this.current = seed;
        }

        @Override
        public long nextId(String bizTag) {
            bizTags.add(bizTag);
            return ++current;
        }

        private List<String> bizTags() {
            return bizTags;
        }
    }
}
