package com.tongji.knowpost.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.common.id.IdService;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.FeedPageCounterState;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import com.tongji.outbox.OutboxMapper;
import com.tongji.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowPostServiceImplDetailHotPathTest {

    @Test
    void localHitReadsCountsAndUserStateWithOneBatchCallAndNoTtlRoundTrip() {
        CounterService counterService = mock(CounterService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HotKeyDetector hotKey = mock(HotKeyDetector.class);
        Cache<String, KnowPostDetailResponse> detailCache = Caffeine.newBuilder().build();
        detailCache.put("knowpost:detail:21:v1", detail("21"));
        when(counterService.getFeedPageStateBatch(
                "knowpost", List.of("21"), 42L, List.of("like", "fav")))
                .thenReturn(Map.of("21", new FeedPageCounterState(
                        Map.of("like", 7L, "fav", 3L), true, false)));
        KnowPostServiceImpl service = new KnowPostServiceImpl(
                mock(KnowPostMapper.class),
                mock(IdService.class),
                new ObjectMapper(),
                mock(MinioStorageService.class),
                counterService,
                mock(UserCounterService.class),
                redis,
                detailCache,
                hotKey,
                mock(OutboxMapper.class));

        KnowPostDetailResponse response = service.getDetail(21L, 42L);

        assertThat(response.likeCount()).isEqualTo(7L);
        assertThat(response.favoriteCount()).isEqualTo(3L);
        assertThat(response.liked()).isTrue();
        assertThat(response.faved()).isFalse();
        verify(counterService).getFeedPageStateBatch(
                "knowpost", List.of("21"), 42L, List.of("like", "fav"));
        verify(counterService, never()).getCounts("knowpost", "21", List.of("like", "fav"));
        verify(counterService, never()).isLiked("knowpost", "21", 42L);
        verify(counterService, never()).isFaved("knowpost", "21", 42L);
        verify(redis, never()).getExpire("knowpost:detail:21:v1");
        verify(redis, never()).getExpire("feed:item:21");
        verify(hotKey).record("knowpost:detail:21:v1");
    }

    private KnowPostDetailResponse detail(String id) {
        return new KnowPostDetailResponse(
                id,
                "title",
                "description",
                "https://example.test/content",
                List.of(),
                List.of("java"),
                "100",
                null,
                "author",
                null,
                1L,
                2L,
                false,
                false,
                false,
                "public",
                "image_text",
                Instant.parse("2026-06-18T10:15:30Z"));
    }
}
