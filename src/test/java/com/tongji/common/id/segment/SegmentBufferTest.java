package com.tongji.common.id.segment;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentBufferTest {

    @Test
    void preloadsExactlyOnceAtHalfConsumptionAndSwitchesToNextRange() {
        RecordingLoader loader = new RecordingLoader(
                new SegmentRange(1, 4),
                new SegmentRange(5, 8)
        );
        DeferredExecutor executor = new DeferredExecutor();
        SegmentBuffer buffer = new SegmentBuffer("reconciliation_task", loader, executor, 500L);

        assertThat(buffer.nextId()).isEqualTo(1L);
        assertThat(executor.submissions()).isZero();
        assertThat(buffer.nextId()).isEqualTo(2L);
        assertThat(executor.submissions()).isEqualTo(1);
        assertThat(loader.invocations()).isEqualTo(1);

        executor.runNext();

        assertThat(loader.bizTags()).containsExactly("reconciliation_task", "reconciliation_task");
        assertThat(buffer.nextId()).isEqualTo(3L);
        assertThat(buffer.nextId()).isEqualTo(4L);
        assertThat(buffer.nextId()).isEqualTo(5L);
        assertThat(executor.submissions()).isEqualTo(1);
    }

    @Test
    void preloadFailureDoesNotCorruptCurrentRangeAndCanRetry() {
        RecordingLoader loader = new RecordingLoader(
                new SegmentRange(1, 4),
                new SegmentLoadException("preload failed"),
                new SegmentRange(5, 8)
        );
        ImmediateExecutor executor = new ImmediateExecutor();
        SegmentBuffer buffer = new SegmentBuffer("admin_operation", loader, executor, 500L);

        assertThat(buffer.nextId()).isEqualTo(1L);
        assertThat(buffer.nextId()).isEqualTo(2L);
        assertThat(buffer.nextId()).isEqualTo(3L);
        assertThat(buffer.nextId()).isEqualTo(4L);
        assertThat(buffer.nextId()).isEqualTo(5L);
        assertThat(loader.invocations()).isEqualTo(3);
    }

    @Test
    void nextIdDoesNotWaitWhenNextRangeBecomesReadyDuringRangeSwitch() throws Exception {
        RecordingLoader loader = new RecordingLoader(new SegmentRange(5, 8));
        SegmentBuffer buffer = new SegmentBuffer("audit_log", loader, new ImmediateExecutor(), 20L);

        setCurrentExhaustedRange(buffer, 1L, 1L);

        long startedAt = System.nanoTime();
        assertThat(buffer.nextId()).isEqualTo(5L);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(20L);
        assertThat(loader.invocations()).isEqualTo(1);
    }

    @Test
    void waitsAtMostConfiguredTimeoutWhenCurrentRangeIsExhausted() {
        long waitTimeoutMs = 20L;
        RecordingLoader loader = new RecordingLoader(new SegmentRange(1, 2));
        BlockingExecutor executor = new BlockingExecutor();
        SegmentBuffer buffer = new SegmentBuffer("audit_log", loader, executor, waitTimeoutMs);

        assertThat(buffer.nextId()).isEqualTo(1L);
        assertThat(buffer.nextId()).isEqualTo(2L);

        long startedAt = System.nanoTime();
        assertThatThrownBy(buffer::nextId)
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("timed out");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(waitTimeoutMs);
        assertThat(elapsedMs).isLessThan(waitTimeoutMs + 40L);
        assertThat(executor.submissions()).isEqualTo(1);
    }

    private static void setCurrentExhaustedRange(SegmentBuffer buffer, long startInclusive, long endInclusive) throws Exception {
        Class<?> cursorClass = Class.forName("com.tongji.common.id.segment.SegmentBuffer$SegmentCursor");
        Constructor<?> constructor = cursorClass.getDeclaredConstructor(SegmentRange.class);
        constructor.setAccessible(true);
        Object cursor = constructor.newInstance(new SegmentRange(startInclusive, endInclusive));

        Field nextValueField = cursorClass.getDeclaredField("nextValue");
        nextValueField.setAccessible(true);
        nextValueField.setLong(cursor, endInclusive + 1L);

        Field currentField = SegmentBuffer.class.getDeclaredField("current");
        currentField.setAccessible(true);
        currentField.set(buffer, cursor);
    }

    private static final class RecordingLoader implements SegmentLoader {
        private final Queue<Object> results = new ArrayDeque<>();
        private final List<String> bizTags = new ArrayList<>();
        private int invocations;

        private RecordingLoader(Object... results) {
            for (Object result : results) {
                this.results.add(result);
            }
        }

        @Override
        public SegmentRange load(String bizTag) {
            invocations++;
            bizTags.add(bizTag);
            Object result = results.poll();
            if (result instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (result == null) {
                throw new AssertionError("No loader result configured");
            }
            return (SegmentRange) result;
        }

        private List<String> bizTags() {
            return bizTags;
        }

        private int invocations() {
            return invocations;
        }
    }

    private static final class ImmediateExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class DeferredExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            tasks.add(command);
        }

        private int submissions() {
            return submissions.get();
        }

        private void runNext() {
            Runnable task = tasks.poll();
            if (task == null) {
                throw new AssertionError("No deferred task configured");
            }
            task.run();
        }
    }

    private static final class BlockingExecutor implements Executor {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
        }

        private int submissions() {
            return submissions.get();
        }
    }
}
