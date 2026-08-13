package com.tongji.comment.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.comment.event.CommentEventReader;
import com.tongji.comment.event.CommentEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CommentCacheInvalidationListener {
    private static final int MAX_PENDING_EVENTS = 10_000;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 10_000L;
    private static final AtomicLong LAST_ERROR_LOG_TIME = new AtomicLong();

    private final Cache<String, CommentBasePage> localCache;
    private final StringRedisTemplate redisTemplate;
    private final CommentEventReader eventReader;
    private final CommentCacheInvalidationScheduler invalidationScheduler;
    private final long invalidationWindowMillis;
    private final Cache<Long, Boolean> recentlyInvalidatedEvents = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final Object pendingMonitor = new Object();
    private final Map<Long, CommentMutationEvent> pendingEvents = new LinkedHashMap<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    public CommentCacheInvalidationListener(
            @Qualifier("commentPageCache") Cache<String, CommentBasePage> localCache,
            StringRedisTemplate redisTemplate,
            CommentEventReader eventReader,
            CommentCacheInvalidationScheduler invalidationScheduler,
            @Value("${comment.cache.invalidation-window-ms:100}") long invalidationWindowMillis) {
        if (invalidationWindowMillis <= 0) {
            throw new IllegalArgumentException("comment cache invalidation window must be positive");
        }
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.eventReader = eventReader;
        this.invalidationScheduler = invalidationScheduler;
        this.invalidationWindowMillis = invalidationWindowMillis;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CommentMutationEvent event) {
        enqueueInvalidation(event);
    }

    @KafkaListener(
            topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.cache-group:comment-cache-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        CommentMutationEvent event = eventReader.mutation(message);
        if (event != null) {
            enqueueInvalidation(event);
        }
    }

    void enqueueInvalidation(CommentMutationEvent event) {
        if (recentlyInvalidatedEvents.getIfPresent(event.eventId()) != null) {
            return;
        }
        boolean overflow = false;
        synchronized (pendingMonitor) {
            if (!pendingEvents.containsKey(event.eventId()) && pendingEvents.size() >= MAX_PENDING_EVENTS) {
                overflow = true;
            } else {
                pendingEvents.putIfAbsent(event.eventId(), event);
            }
        }
        if (overflow) {
            logQueueOverflow();
            return;
        }
        scheduleFlush();
    }

    void flushPendingInvalidations() {
        List<CommentMutationEvent> batch = drainPendingEvents();
        if (batch.isEmpty()) {
            return;
        }
        try {
            invalidateBatch(batch);
            Set<Long> completedEventIds = new HashSet<>(batch.size());
            for (CommentMutationEvent event : batch) {
                completedEventIds.add(event.eventId());
                recentlyInvalidatedEvents.put(event.eventId(), Boolean.TRUE);
            }
            synchronized (pendingMonitor) {
                pendingEvents.keySet().removeAll(completedEventIds);
            }
        } catch (RuntimeException exception) {
            requeue(batch);
            logInvalidationFailure(exception);
        }
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            invalidationScheduler.schedule(this::runScheduledFlush, invalidationWindowMillis);
        } catch (RuntimeException exception) {
            flushScheduled.set(false);
            logInvalidationFailure(exception);
        }
    }

    private void runScheduledFlush() {
        try {
            flushPendingInvalidations();
        } finally {
            flushScheduled.set(false);
            if (hasPendingEvents()) {
                scheduleFlush();
            }
        }
    }

    private List<CommentMutationEvent> drainPendingEvents() {
        synchronized (pendingMonitor) {
            if (pendingEvents.isEmpty()) {
                return List.of();
            }
            List<CommentMutationEvent> batch = new ArrayList<>(pendingEvents.values());
            pendingEvents.clear();
            return batch;
        }
    }

    private void requeue(List<CommentMutationEvent> batch) {
        synchronized (pendingMonitor) {
            for (CommentMutationEvent event : batch) {
                if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
                    break;
                }
                pendingEvents.putIfAbsent(event.eventId(), event);
            }
        }
    }

    private boolean hasPendingEvents() {
        synchronized (pendingMonitor) {
            return !pendingEvents.isEmpty();
        }
    }

    private void invalidateBatch(List<CommentMutationEvent> batch) {
        Set<String> scopeKeys = new HashSet<>();
        Set<String> keysToDelete = new HashSet<>();
        for (CommentMutationEvent event : batch) {
            if (event.eventType() != CommentEventType.COMMENT_CREATED) {
                keysToDelete.add(CommentCacheKeys.item(event.commentId()));
            }
            scopeKeys.add(CommentCacheKeys.postHeadIndex(event.postId()));
            if (event.rootId() > 0) {
                scopeKeys.add(CommentCacheKeys.rootHeadIndex(event.rootId()));
            }
        }

        Set<String> baseKeys = new HashSet<>();
        for (String scopeKey : scopeKeys) {
            Set<String> scopeBaseKeys = redisTemplate.opsForSet().members(scopeKey);
            if (scopeBaseKeys != null) {
                baseKeys.addAll(scopeBaseKeys);
            }
        }
        localCache.invalidateAll(baseKeys);
        for (String baseKey : baseKeys) {
            keysToDelete.add(CommentCacheKeys.indexIds(baseKey));
            keysToDelete.add(CommentCacheKeys.indexCursor(baseKey));
            keysToDelete.add(CommentCacheKeys.indexHasMore(baseKey));
            keysToDelete.add(CommentCacheKeys.indexEmpty(baseKey));
        }
        keysToDelete.addAll(scopeKeys);
        if (!keysToDelete.isEmpty()) {
            redisTemplate.unlink(keysToDelete);
        }
    }

    private void logQueueOverflow() {
        long now = System.currentTimeMillis();
        long previous = LAST_ERROR_LOG_TIME.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MILLIS
                && LAST_ERROR_LOG_TIME.compareAndSet(previous, now)) {
            log.warn("comment cache invalidation queue is full, cache TTL will repair skipped invalidations");
        }
    }

    private void logInvalidationFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = LAST_ERROR_LOG_TIME.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MILLIS
                && LAST_ERROR_LOG_TIME.compareAndSet(previous, now)) {
            log.warn("comment cache invalidation batch failed and will be retried", exception);
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
