package com.tongji.common.id.segment;

import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentIdGeneratorTest {

    @Test
    void segmentIdPropertiesExposeDefaultsAndBindingPrefix() {
        SegmentIdProperties properties = new SegmentIdProperties();

        assertThat(properties.getWaitTimeoutMs()).isEqualTo(500L);
        assertThat(properties.getPreloadThreads()).isEqualTo(2);

        ConfigurationProperties configurationProperties =
                SegmentIdProperties.class.getAnnotation(ConfigurationProperties.class);
        Component component = SegmentIdProperties.class.getAnnotation(Component.class);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("id.segment");
        assertThat(component).isNotNull();
    }

    @Test
    void nextIdUsesAllocatorAndCachesBufferPerBizTag() {
        RecordingAllocator allocator = new RecordingAllocator(
                new SegmentRange(1, 3),
                new SegmentRange(4, 6),
                new SegmentRange(7, 9)
        );
        SegmentIdGenerator generator = new SegmentIdGenerator(allocator, new SegmentIdProperties(), Runnable::run);

        assertThat(generator.nextId("audit_log")).isEqualTo(1L);
        assertThat(generator.nextId("audit_log")).isEqualTo(2L);
        assertThat(generator.nextId("admin_operation")).isEqualTo(7L);

        assertThat(allocator.bizTags()).containsExactly("audit_log", "audit_log", "admin_operation");
        assertThat(generator.bufferCount()).isEqualTo(2);
    }

    @Test
    void wrapsUnknownBizTagFromAllocatorAsSegmentLoadException() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        when(mapper.updateMaxId("audit_log")).thenReturn(0);

        SegmentIdGenerator generator = new SegmentIdGenerator(new SegmentAllocator(mapper), new SegmentIdProperties(), Runnable::run);

        assertThatThrownBy(() -> generator.nextId("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void wrapsUnexpectedUpdatedRowCountFromAllocatorAsSegmentLoadException() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        LeafAlloc leafAlloc = new LeafAlloc();
        leafAlloc.setBizTag("audit_log");
        leafAlloc.setMaxId(2000L);
        leafAlloc.setStep(1000);
        when(mapper.updateMaxId("audit_log")).thenReturn(2);
        when(mapper.selectByBizTag("audit_log")).thenReturn(leafAlloc);

        SegmentIdGenerator generator = new SegmentIdGenerator(new SegmentAllocator(mapper), new SegmentIdProperties(), Runnable::run);

        assertThatThrownBy(() -> generator.nextId("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void wrapsNullReloadAfterSuccessfulUpdateAsSegmentLoadException() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        when(mapper.updateMaxId("audit_log")).thenReturn(1);
        when(mapper.selectByBizTag("audit_log")).thenReturn(null);

        SegmentIdGenerator generator = new SegmentIdGenerator(new SegmentAllocator(mapper), new SegmentIdProperties(), Runnable::run);

        assertThatThrownBy(() -> generator.nextId("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void wrapsMapperExceptionsAsSegmentLoadException() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        when(mapper.updateMaxId("audit_log")).thenThrow(new IllegalStateException("mapper exploded"));

        SegmentIdGenerator generator = new SegmentIdGenerator(new SegmentAllocator(mapper), new SegmentIdProperties(), Runnable::run);

        assertThatThrownBy(() -> generator.nextId("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void treatsNullAllocatorReloadAsSegmentLoadException() {
        SegmentIdGenerator generator = new SegmentIdGenerator(new NullAllocator(), new SegmentIdProperties(), Runnable::run);

        assertThatThrownBy(() -> generator.nextId("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void computeIfAbsentCachesSingleBufferUnderContention() throws Exception {
        CountingAllocator allocator = new CountingAllocator();
        SegmentIdProperties properties = new SegmentIdProperties();
        SegmentIdGenerator generator = new SegmentIdGenerator(allocator, properties, Runnable::run);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    ids.add(generator.nextId("audit_log"));
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } catch (Throwable throwable) {
            failure.set(throwable);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).isNull();
        assertThat(ids).hasSize(threads);
        assertThat(allocator.invocations()).isEqualTo(1);
        assertThat(generator.bufferCount()).isEqualTo(1);
    }

    @Test
    void shutsDownOwnedExecutorOnPreDestroy() {
        RecordingExecutorService executor = new RecordingExecutorService();
        SegmentIdGenerator generator = new SegmentIdGenerator(new RecordingAllocator(new SegmentRange(1, 2)),
                new SegmentIdProperties(), executor, true);

        generator.destroy();

        assertThat(executor.shutdownCalled).isTrue();
    }

    @Test
    void exposesPreDestroyLifecycleHook() throws Exception {
        assertThat(SegmentIdGenerator.class.getMethod("destroy").getAnnotation(PreDestroy.class)).isNotNull();
    }

    private static final class RecordingAllocator extends SegmentAllocator {
        private final List<String> bizTags = new ArrayList<>();
        private final SegmentRange[] ranges;
        private int index;

        private RecordingAllocator(SegmentRange... ranges) {
            super(null);
            this.ranges = ranges;
        }

        @Override
        public SegmentRange allocateSegment(String bizTag) {
            bizTags.add(bizTag);
            if (index >= ranges.length) {
                throw new AssertionError("No more ranges configured");
            }
            return ranges[index++];
        }

        private List<String> bizTags() {
            return bizTags;
        }
    }

    private static final class NullAllocator extends SegmentAllocator {
        private NullAllocator() {
            super(null);
        }

        @Override
        public SegmentRange allocateSegment(String bizTag) {
            return null;
        }
    }

    private static final class CountingAllocator extends SegmentAllocator {
        private long nextStart = 1L;
        private int invocations;

        private CountingAllocator() {
            super(null);
        }

        @Override
        public synchronized SegmentRange allocateSegment(String bizTag) {
            invocations++;
            long start = nextStart;
            nextStart += 100L;
            return new SegmentRange(start, start + 99L);
        }

        private int invocations() {
            return invocations;
        }
    }

    private static final class RecordingExecutorService extends AbstractExecutorService {
        private boolean shutdownCalled;

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownCalled = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdownCalled;
        }

        @Override
        public boolean isTerminated() {
            return shutdownCalled;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdownCalled;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
