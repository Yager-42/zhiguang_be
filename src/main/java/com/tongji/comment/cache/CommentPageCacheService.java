package com.tongji.comment.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.comment.metrics.CommentMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
@Service
public class CommentPageCacheService {
    private static final String SINGLEFLIGHT_STAGE = "comment-page-head";
    private static final String NULL_CURSOR = "-";
    private static final long ERROR_LOG_INTERVAL_MILLIS = 10_000L;
    private static final TypeReference<CommentBasePage> PAGE_TYPE = new TypeReference<>() {
    };
    private static final AtomicLong LAST_ERROR_LOG_TIME = new AtomicLong();

    private final Cache<String, CommentBasePage> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DistributedSingleFlightService singleFlightService;
    private final CommentMetrics metrics;
    private final boolean headCacheEnabled;

    public CommentPageCacheService(@Qualifier("commentPageCache") Cache<String, CommentBasePage> localCache,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   DistributedSingleFlightService singleFlightService,
                                   CommentMetrics metrics,
                                   @Value("${comment.cache.head-enabled:false}") boolean headCacheEnabled) {
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.singleFlightService = singleFlightService;
        this.metrics = metrics;
        this.headCacheEnabled = headCacheEnabled;
    }

    public CommentBasePage getHead(String baseKey, String reverseIndexKey, Supplier<CommentBasePage> loader) {
        if (!headCacheEnabled) {
            return loader.get();
        }
        CommentBasePage local = localCache.getIfPresent(baseKey);
        if (local != null) {
            metrics.cache("l1", "hit");
            return local;
        }
        metrics.cache("l1", "miss");
        CommentBasePage redis = readRedis(baseKey);
        if (redis != null) {
            metrics.cache("l2", redis.items().isEmpty() ? "empty" : "hit");
            if (!redis.items().isEmpty()) {
                localCache.put(baseKey, redis);
            }
            return redis;
        }
        metrics.cache("l2", "miss");
        Timer.Sample sample = metrics.start();
        try {
            CommentBasePage result = singleFlightService.execute(SINGLEFLIGHT_STAGE, baseKey, PAGE_TYPE, () -> {
                CommentBasePage doubleChecked = readRedis(baseKey);
                if (doubleChecked != null) {
                    return doubleChecked;
                }
                CommentBasePage loaded = loader.get();
                write(baseKey, reverseIndexKey, loaded);
                return loaded;
            });
            metrics.l3(sample, "success");
            return result;
        } catch (RuntimeException exception) {
            metrics.l3(sample, "failure");
            throw exception;
        }
    }

    public CommentBasePage readRedis(String baseKey) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(CommentCacheKeys.indexEmpty(baseKey)))) {
                return new CommentBasePage(List.of(), null, null, false);
            }
            List<String> ids = redisTemplate.opsForList().range(CommentCacheKeys.indexIds(baseKey), 0, -1);
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            String cursor = redisTemplate.opsForValue().get(CommentCacheKeys.indexCursor(baseKey));
            String hasMoreValue = redisTemplate.opsForValue().get(CommentCacheKeys.indexHasMore(baseKey));
            if (cursor == null || hasMoreValue == null) {
                return null;
            }
            List<String> fragments = redisTemplate.opsForValue().multiGet(ids.stream()
                    .map(CommentCacheKeys::item)
                    .toList());
            if (fragments == null || fragments.size() != ids.size() || fragments.stream().anyMatch(value -> value == null)) {
                return null;
            }
            List<CommentBaseItem> items = new java.util.ArrayList<>(fragments.size());
            for (int i = 0; i < fragments.size(); i++) {
                CommentBaseItem item = objectMapper.readValue(fragments.get(i), CommentBaseItem.class);
                if (!ids.get(i).equals(item.commentId())) {
                    return null;
                }
                items.add(item);
            }
            LocalDateTime cursorTime = NULL_CURSOR.equals(cursor)
                    ? null : LocalDateTime.parse(cursor.substring(0, cursor.lastIndexOf('|')));
            String cursorId = NULL_CURSOR.equals(cursor) ? null : cursor.substring(cursor.lastIndexOf('|') + 1);
            return new CommentBasePage(items, cursorTime, cursorId, Boolean.parseBoolean(hasMoreValue));
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.cache("l2", "error");
            logCacheError("comment page Redis read failed, cache treated as miss", exception);
            return null;
        }
    }

    public void write(String baseKey, String reverseIndexKey, CommentBasePage page) {
        try {
            if (page.items().isEmpty()) {
                Duration emptyTtl = Duration.ofSeconds(ThreadLocalRandom.current().nextLong(5, 11));
                redisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    public <K, V> Object execute(RedisOperations<K, V> operations) {
                        @SuppressWarnings("unchecked")
                        RedisOperations<String, String> stringOperations =
                                (RedisOperations<String, String>) operations;
                        stringOperations.opsForValue().set(CommentCacheKeys.indexEmpty(baseKey), "1", emptyTtl);
                        stringOperations.opsForSet().add(reverseIndexKey, baseKey);
                        stringOperations.expire(reverseIndexKey, Duration.ofMinutes(10));
                        return null;
                    }
                });
                return;
            }
            List<SerializedCacheItem> serializedItems = new java.util.ArrayList<>(page.items().size());
            for (CommentBaseItem item : page.items()) {
                serializedItems.add(new SerializedCacheItem(
                        CommentCacheKeys.item(item.commentId()),
                        objectMapper.writeValueAsString(item),
                        Duration.ofSeconds(ThreadLocalRandom.current().nextLong(300, 601))));
            }
            long indexTtlSeconds = ThreadLocalRandom.current().nextLong(20, 31);
            List<String> ids = page.items().stream().map(CommentBaseItem::commentId).toList();
            String cursor = page.nextCursorCreateTime() == null || page.nextCursorCommentId() == null
                    ? NULL_CURSOR : page.nextCursorCreateTime() + "|" + page.nextCursorCommentId();
            redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> stringOperations =
                            (RedisOperations<String, String>) operations;
                    for (SerializedCacheItem item : serializedItems) {
                        stringOperations.opsForValue().set(item.key(), item.value(), item.ttl());
                    }
                    return null;
                }
            });
            redisTemplate.execute(new SessionCallback<List<Object>>() {
                @Override
                public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> stringOperations =
                            (RedisOperations<String, String>) operations;
                    stringOperations.multi();
                    stringOperations.delete(CommentCacheKeys.indexEmpty(baseKey));
                    stringOperations.delete(CommentCacheKeys.indexIds(baseKey));
                    stringOperations.opsForList().rightPushAll(CommentCacheKeys.indexIds(baseKey), ids);
                    stringOperations.expire(CommentCacheKeys.indexIds(baseKey), Duration.ofSeconds(indexTtlSeconds));
                    stringOperations.opsForValue().set(CommentCacheKeys.indexCursor(baseKey), cursor,
                            Duration.ofSeconds(indexTtlSeconds));
                    stringOperations.opsForValue().set(CommentCacheKeys.indexHasMore(baseKey),
                            Boolean.toString(page.hasMore()), Duration.ofSeconds(indexTtlSeconds));
                    stringOperations.opsForSet().add(reverseIndexKey, baseKey);
                    stringOperations.expire(reverseIndexKey, Duration.ofMinutes(10));
                    return stringOperations.exec();
                }
            });
            localCache.put(baseKey, page);
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.cache("l2", "write_error");
            logCacheError("comment page cache write failed", exception);
        }
    }

    private void logCacheError(String message, Exception exception) {
        long now = System.currentTimeMillis();
        long previous = LAST_ERROR_LOG_TIME.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MILLIS
                && LAST_ERROR_LOG_TIME.compareAndSet(previous, now)) {
            log.warn(message, exception);
        }
    }

    private record SerializedCacheItem(String key, String value, Duration ttl) {
    }
}
