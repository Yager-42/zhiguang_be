package com.tongji.common.id.segment;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SegmentIdGenerator {
    private final SegmentAllocator allocator;
    private final SegmentIdProperties properties;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final ConcurrentHashMap<String, SegmentBuffer> buffers = new ConcurrentHashMap<>();

    public SegmentIdGenerator(SegmentAllocator allocator, SegmentIdProperties properties) {
        this(allocator, properties,
                Executors.newFixedThreadPool(Math.max(1, properties.getPreloadThreads())),
                true);
    }

    public SegmentIdGenerator(SegmentAllocator allocator, SegmentIdProperties properties, Executor executor) {
        this(allocator, properties, executor, false);
    }

    SegmentIdGenerator(SegmentAllocator allocator, SegmentIdProperties properties, Executor executor, boolean ownsExecutor) {
        this.allocator = allocator;
        this.properties = properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public long nextId(String bizTag) {
        Objects.requireNonNull(bizTag, "bizTag");
        return buffers.computeIfAbsent(bizTag, this::newBuffer).nextId();
    }

    int bufferCount() {
        return buffers.size();
    }

    @PreDestroy
    public void destroy() {
        if (ownsExecutor && executor instanceof ExecutorService executorService) {
            executorService.shutdown();
        }
    }

    private SegmentBuffer newBuffer(String bizTag) {
        return new SegmentBuffer(bizTag, this::loadSegment, executor, properties.getWaitTimeoutMs());
    }

    private SegmentRange loadSegment(String bizTag) {
        try {
            SegmentRange range = allocator.allocateSegment(bizTag);
            if (range == null) {
                throw new SegmentLoadException("Segment allocator returned null for bizTag=" + bizTag);
            }
            return range;
        } catch (RuntimeException e) {
            throw new SegmentLoadException("Failed to load segment for bizTag=" + bizTag, e);
        }
    }
}
