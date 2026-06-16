package com.tongji.common.id;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    @Test
    void snowflakePropertiesExposeDefaultValuesAndAnnotations() {
        SnowflakeProperties properties = new SnowflakeProperties();

        assertThat(properties.getWorkerId()).isEqualTo(1L);
        assertThat(properties.getDatacenterId()).isEqualTo(1L);

        ConfigurationProperties configurationProperties =
                SnowflakeProperties.class.getAnnotation(ConfigurationProperties.class);
        Component component = SnowflakeProperties.class.getAnnotation(Component.class);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("id.snowflake");
        assertThat(component).isNotNull();
    }

    @Test
    void snowflakeGeneratorUsesExplicitComponentName() {
        Component component = SnowflakeIdGenerator.class.getAnnotation(Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("commonSnowflakeIdGenerator");
    }

    @Test
    void rejectsInvalidWorkerAndDatacenterIds() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workerId out of range");

        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workerId out of range");

        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("datacenterId out of range");

        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("datacenterId out of range");
    }

    @Test
    void encodesTimestampWorkerDatacenterAndSequenceIntoExpectedBitLayout() {
        TestableSnowflakeIdGenerator generator = new TestableSnowflakeIdGenerator(7, 9, 1704067201234L, 1704067201234L);

        long firstId = generator.nextId();
        long secondId = generator.nextId();

        assertThat(extractTimestamp(firstId)).isEqualTo(1704067201234L);
        assertThat(extractDatacenterId(firstId)).isEqualTo(7L);
        assertThat(extractWorkerId(firstId)).isEqualTo(9L);
        assertThat(extractSequence(firstId)).isZero();

        assertThat(extractTimestamp(secondId)).isEqualTo(1704067201234L);
        assertThat(extractDatacenterId(secondId)).isEqualTo(7L);
        assertThat(extractWorkerId(secondId)).isEqualTo(9L);
        assertThat(extractSequence(secondId)).isEqualTo(1L);
    }

    @Test
    void snowflakeNamespacesUseCommonGenerator() {
        RecordingSnowflakeIdGenerator generator = new RecordingSnowflakeIdGenerator(1000L);
        DefaultIdService idService = new DefaultIdService(generator);

        assertThat(idService.nextId(IdNamespace.POST)).isEqualTo(1001L);
        assertThat(idService.nextId(IdNamespace.COMMENT)).isEqualTo(1002L);
        assertThat(idService.nextId(IdNamespace.PENDING_COMMENT)).isEqualTo(1003L);
        assertThat(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).isEqualTo(1004L);
        assertThat(idService.nextId(IdNamespace.RELATION)).isEqualTo(1005L);
        assertThat(idService.nextId(IdNamespace.OUTBOX_EVENT)).isEqualTo(1006L);

        assertThat(generator.invocations()).isEqualTo(6L);
    }

    @Test
    void segmentNamespacesThrowUntilSegmentGeneratorExists() {
        RecordingSnowflakeIdGenerator generator = new RecordingSnowflakeIdGenerator(2000L);
        DefaultIdService idService = new DefaultIdService(generator);

        assertThatThrownBy(() -> idService.nextId(IdNamespace.RECONCILIATION_TASK))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Segment");

        assertThatThrownBy(() -> idService.nextId(IdNamespace.ADMIN_OPERATION))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Segment");

        assertThatThrownBy(() -> idService.nextId(IdNamespace.AUDIT_LOG))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Segment");

        assertThat(generator.invocations()).isZero();
    }

    @Test
    void waitsForSmallClockRollbackThenContinues() {
        TestableSnowflakeIdGenerator generator = new TestableSnowflakeIdGenerator(1, 1, 100L, 97L, 100L);

        long firstId = generator.nextId();
        long secondId = generator.nextId();

        assertThat(secondId).isGreaterThan(firstId);
        assertThat(generator.lastSleepMillis).isEqualTo(3L);
    }

    @Test
    void throwsOnLargeClockRollback() {
        TestableSnowflakeIdGenerator generator = new TestableSnowflakeIdGenerator(1, 1, 200L, 194L);

        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(ClockBackwardException.class)
                .hasMessageContaining("offset=6ms");
        assertThat(generator.lastSleepMillis).isZero();
    }

    @Test
    void waitsForNextMillisecondAfter4096IdsInSameMillisecond() {
        long sameMillisecond = 1704067205555L;
        TestableSnowflakeIdGenerator generator = new TestableSnowflakeIdGenerator(
                3,
                5,
                repeatedTimes(sameMillisecond, 4098, sameMillisecond + 1)
        );

        long id4096 = 0L;
        long id4097 = 0L;
        for (int i = 0; i < 4097; i++) {
            long id = generator.nextId();
            if (i == 4095) {
                id4096 = id;
            }
            if (i == 4096) {
                id4097 = id;
            }
        }

        assertThat(extractTimestamp(id4096)).isEqualTo(sameMillisecond);
        assertThat(extractSequence(id4096)).isEqualTo(4095L);
        assertThat(extractTimestamp(id4097)).isEqualTo(sameMillisecond + 1);
        assertThat(extractSequence(id4097)).isZero();
    }

    @Test
    void producesUniqueIdsAcrossConcurrentCalls() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int threads = 16;
        int idsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start");
                    }
                    for (int j = 0; j < idsPerThread; j++) {
                        ids.add(generator.nextId());
                    }
                    return null;
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failure.compareAndSet(null, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                } catch (TimeoutException e) {
                    failure.compareAndSet(null, e);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(failure.get()).isNull();
        assertThat(ids).hasSize(threads * idsPerThread);
    }

    private static long extractTimestamp(long id) {
        return (id >>> 22) + SnowflakeIdGenerator.EPOCH;
    }

    private static long extractDatacenterId(long id) {
        return (id >>> 17) & 0x1FL;
    }

    private static long extractWorkerId(long id) {
        return (id >>> 12) & 0x1FL;
    }

    private static long extractSequence(long id) {
        return id & 0xFFFL;
    }

    private static long[] repeatedTimes(long repeatedValue, int repeatedCount, long finalValue) {
        long[] times = new long[repeatedCount + 1];
        for (int i = 0; i < repeatedCount; i++) {
            times[i] = repeatedValue;
        }
        times[repeatedCount] = finalValue;
        return times;
    }

    private static final class TestableSnowflakeIdGenerator extends SnowflakeIdGenerator {
        private final long[] times;
        private int index;
        private long lastSleepMillis;

        private TestableSnowflakeIdGenerator(long datacenterId, long workerId, long... times) {
            super(datacenterId, workerId);
            this.times = times;
        }

        @Override
        long currentTime() {
            int currentIndex = Math.min(index, times.length - 1);
            long value = times[currentIndex];
            index++;
            return value;
        }

        @Override
        void sleepMillis(long millis) {
            lastSleepMillis = millis;
        }
    }

    private static final class RecordingSnowflakeIdGenerator extends SnowflakeIdGenerator {
        private final AtomicLong ids;
        private final AtomicLong invocations = new AtomicLong();

        private RecordingSnowflakeIdGenerator(long seed) {
            super(1, 1);
            this.ids = new AtomicLong(seed);
        }

        @Override
        public synchronized long nextId() {
            invocations.incrementAndGet();
            return ids.incrementAndGet();
        }

        private long invocations() {
            return invocations.get();
        }
    }
}
