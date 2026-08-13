package com.tongji.knowpost.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.id.IdService;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.FeedPageCounterState;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import com.tongji.outbox.OutboxMapper;
import com.tongji.storage.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowPostDetailSingleFlightTest {
    private KnowPostMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CounterService counterService;
    private DistributedSingleFlightService singleFlightService;
    private Cache<String, KnowPostDetailResponse> detailCache;
    private KnowPostServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(KnowPostMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        counterService = mock(CounterService.class);
        singleFlightService = mock(DistributedSingleFlightService.class);
        detailCache = Caffeine.newBuilder().build();
        when(redis.opsForValue()).thenReturn(values);
        when(singleFlightService.execute(eq("knowpost-detail"), anyString(), any(TypeReference.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(3, Supplier.class).get());
        service = new KnowPostServiceImpl(
                mapper,
                mock(IdService.class),
                new ObjectMapper().findAndRegisterModules(),
                mock(MinioStorageService.class),
                counterService,
                mock(UserCounterService.class),
                redis,
                detailCache,
                mock(HotKeyDetector.class),
                mock(OutboxMapper.class),
                singleFlightService
        );
    }

    @Test
    void coldLoadUsesViewerScopedFlightAndAppliesViewerStateAfterFlight() {
        when(values.get("knowpost:detail:21:v1")).thenReturn(null, null);
        when(mapper.findDetailById(21L)).thenReturn(row("public", 100L));
        when(counterService.getFeedPageStateBatch("knowpost", List.of("21"), 42L, List.of("like", "fav")))
                .thenReturn(Map.of("21", new FeedPageCounterState(Map.of("like", 7L, "fav", 3L), true, false)));

        KnowPostDetailResponse result = service.getDetail(21L, 42L);

        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(7L);
        verify(singleFlightService).execute(
                eq("knowpost-detail"),
                eq("knowpost:detail:21:v1:viewer:42"),
                any(TypeReference.class),
                any(Supplier.class));
    }

    @Test
    void privateDetailCachedByOwnerIsRejectedForAnotherViewer() {
        detailCache.put("knowpost:detail:21:v1", detail("private", "100"));

        assertThatThrownBy(() -> service.getDetail(21L, 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权限查看");

        verify(counterService, never()).getFeedPageStateBatch(anyString(), any(), any(), any());
        verify(singleFlightService, never()).execute(anyString(), anyString(), any(TypeReference.class), any(Supplier.class));
    }

    @Test
    void privateDetailRemainsVisibleToOwner() {
        detailCache.put("knowpost:detail:21:v1", detail("private", "100"));
        when(counterService.getFeedPageStateBatch("knowpost", List.of("21"), 100L, List.of("like", "fav")))
                .thenReturn(Map.of());

        assertThat(service.getDetail(21L, 100L).id()).isEqualTo("21");
    }

    private static KnowPostDetailRow row(String visible, long creatorId) {
        KnowPostDetailRow row = new KnowPostDetailRow();
        row.setId(21L);
        row.setCreatorId(creatorId);
        row.setStatus("published");
        row.setVisible(visible);
        row.setTitle("title");
        row.setType("image_text");
        row.setPublishTime(Instant.parse("2026-06-18T10:15:30Z"));
        return row;
    }

    private static KnowPostDetailResponse detail(String visible, String authorId) {
        return new KnowPostDetailResponse(
                "21", "title", "description", null, List.of(), List.of(), authorId,
                null, "author", null, 1L, 2L, null, null, false, visible,
                "image_text", Instant.parse("2026-06-18T10:15:30Z"));
    }
}
