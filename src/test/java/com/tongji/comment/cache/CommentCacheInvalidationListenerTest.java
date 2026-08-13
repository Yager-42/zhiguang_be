package com.tongji.comment.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.event.CommentEventReader;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentCacheInvalidationListenerTest {

    @Test
    void coalescesEventsByScopeAndUnlinksCacheKeysInOneBatch() throws Exception {
        Cache<String, CommentBasePage> cache = Caffeine.newBuilder().build();
        cache.put("post-head", new CommentBasePage(java.util.List.of(), null, null, false));
        cache.put("root-head", new CommentBasePage(java.util.List.of(), null, null, false));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members(CommentCacheKeys.postHeadIndex(9L))).thenReturn(Set.of("post-head"));
        when(sets.members(CommentCacheKeys.rootHeadIndex(11L))).thenReturn(Set.of("root-head"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CommentCacheInvalidationListener listener = new CommentCacheInvalidationListener(
                cache, redis, new CommentEventReader(objectMapper),
                mock(CommentCacheInvalidationScheduler.class), 100L);
        CommentMutationEvent deleted = new CommentMutationEvent(
                201L, CommentEventType.COMMENT_DELETED, 12L, 9L, 11L, 11L);
        CommentMutationEvent created = new CommentMutationEvent(
                202L, CommentEventType.COMMENT_CREATED, 13L, 9L, 11L, 11L);
        CommentOutboxEvent duplicate = new CommentOutboxEvent(
                201L, CommentEventType.COMMENT_DELETED, 12L, 9L, 11L, 11L, 7L,
                "client-1", null, LocalDateTime.of(2026, 8, 7, 10, 0));

        listener.afterCommit(deleted);
        listener.afterCommit(created);
        listener.onMessage(objectMapper.writeValueAsString(duplicate));
        listener.flushPendingInvalidations();

        assertThat(cache.getIfPresent("post-head")).isNull();
        assertThat(cache.getIfPresent("root-head")).isNull();
        verify(sets, times(1)).members(CommentCacheKeys.postHeadIndex(9L));
        verify(sets, times(1)).members(CommentCacheKeys.rootHeadIndex(11L));
        verify(redis).unlink(argThat((Collection<String> keys) ->
                keys.contains(CommentCacheKeys.item(12L))
                        && keys.contains(CommentCacheKeys.indexIds("post-head"))
                        && keys.contains(CommentCacheKeys.indexIds("root-head"))
                        && keys.contains(CommentCacheKeys.postHeadIndex(9L))
                        && keys.contains(CommentCacheKeys.rootHeadIndex(11L))));

        listener.onMessage(objectMapper.writeValueAsString(duplicate));
        listener.flushPendingInvalidations();

        verify(sets, times(1)).members(CommentCacheKeys.postHeadIndex(9L));
    }
}
