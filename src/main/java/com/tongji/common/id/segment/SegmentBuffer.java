package com.tongji.common.id.segment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;

public class SegmentBuffer {
    private static final Logger log = LoggerFactory.getLogger(SegmentBuffer.class);

    private final String bizTag;
    private final SegmentLoader loader;
    private final Executor executor;
    private final long waitTimeoutMs;

    private SegmentCursor current;
    private SegmentCursor next;
    private boolean loading;

    public SegmentBuffer(String bizTag, SegmentLoader loader, Executor executor, long waitTimeoutMs) {
        this.bizTag = Objects.requireNonNull(bizTag, "bizTag");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.waitTimeoutMs = waitTimeoutMs;
    }

    public synchronized long nextId() {
        if (current == null) {
            current = new SegmentCursor(loadRange());
        }

        if (current.isExhausted()) {
            moveToNextRange();
        }

        long id = current.nextId();
        if (shouldPreload()) {
            startLoadingIfNeeded();
        }
        return id;
    }

    private void moveToNextRange() {
        long deadline = System.nanoTime() + (waitTimeoutMs * 1_000_000L);
        while (current.isExhausted()) {
            if (next != null) {
                current = next;
                next = null;
                return;
            }

            startLoadingIfNeeded();

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new SegmentLoadException("Segment load timed out for bizTag=" + bizTag);
            }

            if (next != null) {
                continue;
            }

            long waitMillis = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
            try {
                wait(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SegmentLoadException("Interrupted while waiting for next segment for bizTag=" + bizTag, e);
            }
        }
    }

    private boolean shouldPreload() {
        return next == null && current.consumedCount() * 2L >= current.size();
    }

    private void startLoadingIfNeeded() {
        if (loading || next != null) {
            return;
        }

        loading = true;
        executor.execute(() -> {
            SegmentCursor loaded = null;
            try {
                loaded = new SegmentCursor(loadRange());
            } catch (RuntimeException e) {
                log.warn("Async preload failed for bizTag={}", bizTag, e);
                loaded = null;
            } finally {
                synchronized (SegmentBuffer.this) {
                    if (loaded != null) {
                        next = loaded;
                    }
                    loading = false;
                    SegmentBuffer.this.notifyAll();
                }
            }
        });
    }

    private SegmentRange loadRange() {
        try {
            SegmentRange range = loader.load(bizTag);
            if (range == null) {
                throw new SegmentLoadException("Segment loader returned null for bizTag=" + bizTag);
            }
            return range;
        } catch (SegmentLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SegmentLoadException("Failed to load segment for bizTag=" + bizTag, e);
        }
    }

    private static final class SegmentCursor {
        private final long startInclusive;
        private final long endInclusive;
        private long nextValue;

        private SegmentCursor(SegmentRange range) {
            this.startInclusive = range.getStartInclusive();
            this.endInclusive = range.getEndInclusive();
            this.nextValue = startInclusive;
        }

        private long nextId() {
            if (isExhausted()) {
                throw new IllegalStateException("segment exhausted");
            }
            return nextValue++;
        }

        private boolean isExhausted() {
            return nextValue > endInclusive;
        }

        private long size() {
            return endInclusive - startInclusive + 1L;
        }

        private long consumedCount() {
            return nextValue - startInclusive;
        }
    }
}
