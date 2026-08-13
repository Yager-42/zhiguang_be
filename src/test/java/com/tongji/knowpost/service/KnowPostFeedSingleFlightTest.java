package com.tongji.knowpost.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.FeedPageCounterState;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostFeedRow;
import com.tongji.knowpost.service.impl.KnowPostFeedServiceImpl;
import com.tongji.promotion.service.PromotionAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowPostFeedSingleFlightTest {
    private KnowPostMapper mapper;
    private StringRedisTemplate redis;
    private ListOperations<String, String> lists;
    private SetOperations<String, String> sets;
    private ValueOperations<String, String> values;
    private CounterService counterService;
    private DistributedSingleFlightService singleFlightService;
    private KnowPostFeedServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(KnowPostMapper.class);
        redis = mock(StringRedisTemplate.class);
        sets = mock(SetOperations.class);
        lists = mock(ListOperations.class);
        values = mock(ValueOperations.class);
        counterService = mock(CounterService.class);
        singleFlightService = mock(DistributedSingleFlightService.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(singleFlightService.execute(eq("knowpost-public-feed"), anyString(), any(TypeReference.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(3, Supplier.class).get());
        Cache<String, FeedPageResponse> publicCache = Caffeine.newBuilder().build();
        service = new KnowPostFeedServiceImpl(
                mapper,
                redis,
                new ObjectMapper().findAndRegisterModules(),
                counterService,
                publicCache,
                Caffeine.newBuilder().build(),
                mock(HotKeyDetector.class),
                mock(PromotionAllocationService.class),
                singleFlightService
        );
    }

    @Test
    void coldLoadSharesOnlyBasePageAndAppliesViewerStateAfterFlight() {
        when(lists.range(anyString(), eq(0L), eq(19L))).thenReturn(null);
        when(mapper.listFeedPublic(21, 0)).thenReturn(List.of(row(11L)));
        when(counterService.getFeedPageStateBatch("knowpost", List.of("11"), 42L, List.of("like", "fav")))
                .thenReturn(Map.of("11", new FeedPageCounterState(Map.of("like", 7L, "fav", 3L), true, false)));

        FeedPageResponse result = service.getPublicFeed(1, 20, 42L);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.likeCount()).isEqualTo(7L);
            assertThat(item.favoriteCount()).isEqualTo(3L);
            assertThat(item.liked()).isTrue();
            assertThat(item.faved()).isFalse();
        });
        verify(singleFlightService).execute(
                eq("knowpost-public-feed"),
                org.mockito.ArgumentMatchers.contains("feed:public:ids:20:"),
                any(TypeReference.class),
                any(Supplier.class));
        verify(counterService).getFeedPageStateBatch("knowpost", List.of("11"), 42L, List.of("like", "fav"));
    }

    private static KnowPostFeedRow row(long id) {
        KnowPostFeedRow row = new KnowPostFeedRow();
        row.setId(id);
        row.setTitle("title");
        row.setDescription("description");
        row.setPublishTime(Instant.parse("2026-06-18T10:15:30Z"));
        row.setIsTop(false);
        return row;
    }
}
