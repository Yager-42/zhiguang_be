package com.tongji.comment.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.comment.metrics.CommentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentPageCacheServiceTest {
    private Cache<String, CommentBasePage> localCache;
    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private ValueOperations<String, String> valueOperations;
    private DistributedSingleFlightService singleFlightService;
    private CommentPageCacheService service;

    @BeforeEach
    void setUp() {
        localCache = Caffeine.newBuilder().maximumSize(10).build();
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        valueOperations = mock(ValueOperations.class);
        singleFlightService = mock(DistributedSingleFlightService.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new CommentPageCacheService(localCache, redisTemplate,
                new ObjectMapper().findAndRegisterModules(), singleFlightService,
                new CommentMetrics(new SimpleMeterRegistry()), true);
    }

    @Test
    void l1HitSkipsRedisAndLoader() {
        CommentBasePage page = page("11");
        localCache.put("head", page);
        @SuppressWarnings("unchecked")
        Supplier<CommentBasePage> loader = mock(Supplier.class);

        assertThat(service.getHead("head", "reverse", loader)).isSameAs(page);

        verify(redisTemplate, never()).hasKey(anyString());
        verify(loader, never()).get();
    }

    @Test
    void disabledHeadCacheAlwaysLoadsFreshPage() {
        CommentPageCacheService uncachedService = new CommentPageCacheService(
                localCache, redisTemplate, new ObjectMapper().findAndRegisterModules(),
                singleFlightService, new CommentMetrics(new SimpleMeterRegistry()), false);
        CommentBasePage stale = page("11");
        CommentBasePage fresh = page("12");
        localCache.put("head", stale);

        CommentBasePage result = uncachedService.getHead("head", "reverse", () -> fresh);

        assertThat(result).isSameAs(fresh);
        verify(redisTemplate, never()).hasKey(anyString());
        verify(singleFlightService, never()).execute(anyString(), anyString(), any(), any());
    }

    @Test
    void missingFragmentTreatsWholeRedisPageAsMiss() {
        when(redisTemplate.hasKey(CommentCacheKeys.indexEmpty("head"))).thenReturn(false);
        when(listOperations.range(CommentCacheKeys.indexIds("head"), 0, -1)).thenReturn(List.of("11", "12"));
        when(valueOperations.get(CommentCacheKeys.indexCursor("head"))).thenReturn("-");
        when(valueOperations.get(CommentCacheKeys.indexHasMore("head"))).thenReturn("false");
        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList("{}", null));

        assertThat(service.readRedis("head")).isNull();
    }

    @Test
    void emptyMarkerProtectsEmptyPageWithoutReadingFragments() {
        when(redisTemplate.hasKey(CommentCacheKeys.indexEmpty("head"))).thenReturn(true);

        CommentBasePage result = service.readRedis("head");

        assertThat(result.items()).isEmpty();
        verify(listOperations, never()).range(anyString(), any(Long.class), any(Long.class));
    }

    @Test
    void l3LoadRunsThroughExistingDistributedSingleFlight() {
        when(redisTemplate.hasKey(CommentCacheKeys.indexEmpty("head"))).thenReturn(false);
        when(listOperations.range(CommentCacheKeys.indexIds("head"), 0, -1)).thenReturn(null);
        when(singleFlightService.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(3);
            return supplier.get();
        });
        CommentBasePage expected = page("11");

        CommentBasePage result = service.getHead("head", "reverse", () -> expected);

        assertThat(result).isSameAs(expected);
        verify(singleFlightService).execute(anyString(), anyString(), any(), any());
        verify(redisTemplate).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
        verify(redisTemplate).execute(any(org.springframework.data.redis.core.SessionCallback.class));
    }

    private CommentBasePage page(String commentId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        CommentBaseItem item = new CommentBaseItem(commentId, "9", null, null, "7", "body",
                0, false, 0, 0, now, now);
        return new CommentBasePage(List.of(item), now, commentId, false);
    }
}
