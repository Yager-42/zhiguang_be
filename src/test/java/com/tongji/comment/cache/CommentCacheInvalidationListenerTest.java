package com.tongji.comment.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.comment.event.CommentEventType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentCacheInvalidationListenerTest {

    @Test
    void invalidatesPostRootAndDeletedItem() {
        Cache<String, CommentBasePage> cache = Caffeine.newBuilder().build();
        cache.put("post-head", new CommentBasePage(java.util.List.of(), null, null, false));
        cache.put("root-head", new CommentBasePage(java.util.List.of(), null, null, false));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members(CommentCacheKeys.postHeadIndex(9L))).thenReturn(Set.of("post-head"));
        when(sets.members(CommentCacheKeys.rootHeadIndex(11L))).thenReturn(Set.of("root-head"));
        CommentCacheInvalidationListener listener = new CommentCacheInvalidationListener(
                cache, redis, new ObjectMapper().findAndRegisterModules());

        listener.invalidate(new CommentMutationEvent(CommentEventType.COMMENT_DELETED, 12L, 9L, 11L, 11L));

        assertThat(cache.getIfPresent("post-head")).isNull();
        assertThat(cache.getIfPresent("root-head")).isNull();
        verify(redis).delete(CommentCacheKeys.item(12L));
        verify(redis).delete(CommentCacheKeys.indexIds("post-head"));
        verify(redis).delete(CommentCacheKeys.indexIds("root-head"));
    }
}
