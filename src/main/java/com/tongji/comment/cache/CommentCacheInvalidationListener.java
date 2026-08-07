package com.tongji.comment.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

@Slf4j
@Component
public class CommentCacheInvalidationListener {
    private final Cache<String, CommentBasePage> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CommentCacheInvalidationListener(
            @Qualifier("commentPageCache") Cache<String, CommentBasePage> localCache,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CommentMutationEvent event) {
        invalidate(event);
    }

    @KafkaListener(
            topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.cache-group:comment-cache-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        try {
            CommentOutboxEvent event = objectMapper.readValue(message, CommentOutboxEvent.class);
            if (event.eventType() == CommentEventType.COMMENT_CREATED
                    || event.eventType() == CommentEventType.COMMENT_DELETED
                    || event.eventType() == CommentEventType.COMMENT_MODERATED) {
                invalidate(new CommentMutationEvent(event.eventType(), event.commentId(), event.postId(),
                        value(event.rootId()), value(event.parentId())));
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment cache event", exception);
        }
    }

    public void invalidate(CommentMutationEvent event) {
        try {
            if (event.eventType() != CommentEventType.COMMENT_CREATED) {
                redisTemplate.delete(CommentCacheKeys.item(event.commentId()));
            }
            invalidateScope(CommentCacheKeys.postHeadIndex(event.postId()));
            if (event.rootId() > 0) {
                invalidateScope(CommentCacheKeys.rootHeadIndex(event.rootId()));
            }
        } catch (RuntimeException exception) {
            log.warn("comment cache invalidation failed, eventType={}", event.eventType(), exception);
        }
    }

    private void invalidateScope(String reverseIndexKey) {
        Set<String> baseKeys = redisTemplate.opsForSet().members(reverseIndexKey);
        if (baseKeys == null || baseKeys.isEmpty()) {
            return;
        }
        for (String baseKey : baseKeys) {
            localCache.invalidate(baseKey);
            redisTemplate.delete(CommentCacheKeys.indexIds(baseKey));
            redisTemplate.delete(CommentCacheKeys.indexCursor(baseKey));
            redisTemplate.delete(CommentCacheKeys.indexHasMore(baseKey));
            redisTemplate.delete(CommentCacheKeys.indexEmpty(baseKey));
        }
        redisTemplate.delete(reverseIndexKey);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
