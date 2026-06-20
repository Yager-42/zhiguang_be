package com.tongji.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostFeedRow;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.impl.KnowPostFeedServiceImpl;
import com.tongji.promotion.service.PromotionAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeFeedMixingHydrationTest {

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private CounterService counterService;

    @Mock
    private Cache<String, FeedPageResponse> feedPublicCache;

    @Mock
    private Cache<String, FeedPageResponse> feedMineCache;

    @Mock
    private HotKeyDetector hotKeyDetector;

    @Mock
    private PromotionAllocationService promotionAllocationService;

    private KnowPostFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowPostFeedServiceImpl(
                knowPostMapper,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                counterService,
                feedPublicCache,
                feedMineCache,
                hotKeyDetector,
                promotionAllocationService
        );
    }

    @Test
    void sourceAwareHydrationPreservesInputOrder() {
        when(knowPostMapper.listFeedByIds(List.of(11L, 12L), null, false)).thenReturn(List.of(
                feedRow(12L, "2026-06-18T10:15:29Z"),
                feedRow(11L, "2026-06-18T10:15:30Z")
        ));
        when(counterService.getCounts(eq("knowpost"), anyString(), anyList())).thenReturn(Map.of("like", 0L, "fav", 0L));

        List<FeedItemResponse> items = service.getFeedByIds(List.of(11L, 12L), null, KnowPostFeedService.FeedVisibilityScope.PUBLIC);

        assertThat(items).extracting(FeedItemResponse::id).containsExactly("11", "12");
    }

    @Test
    void sourceAwareHydrationUsesFollowVisibilityForFollowScope() {
        when(knowPostMapper.listFeedByIds(List.of(21L), 42L, true)).thenReturn(List.of(feedRow(21L, "2026-06-18T10:15:30Z")));
        when(counterService.getCounts(eq("knowpost"), anyString(), anyList())).thenReturn(Map.of("like", 0L, "fav", 0L));

        List<FeedItemResponse> items = service.getFeedByIds(List.of(21L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW);

        assertThat(items).extracting(FeedItemResponse::id).containsExactly("21");
    }

    @Test
    void publicHydrationExcludesFollowersOnlyRowsWhileFollowHydrationAllowsThem() {
        when(knowPostMapper.listFeedByIds(List.of(31L), 42L, false)).thenReturn(List.of());
        when(knowPostMapper.listFeedByIds(List.of(31L), 42L, true)).thenReturn(List.of(feedRow(31L, "2026-06-18T10:15:30Z")));
        when(counterService.getCounts(eq("knowpost"), anyString(), anyList())).thenReturn(Map.of("like", 0L, "fav", 0L));

        List<FeedItemResponse> publicItems = service.getFeedByIds(List.of(31L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
        List<FeedItemResponse> followItems = service.getFeedByIds(List.of(31L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW);

        assertThat(publicItems).isEmpty();
        assertThat(followItems).extracting(FeedItemResponse::id).containsExactly("31");
    }

    @Test
    void hydrationDropsDeletedRequestedCandidates() {
        when(knowPostMapper.listFeedByIds(List.of(41L, 42L), null, false)).thenReturn(List.of(
                feedRow(42L, "2026-06-18T10:15:29Z")
        ));
        when(counterService.getCounts(eq("knowpost"), anyString(), anyList())).thenReturn(Map.of("like", 0L, "fav", 0L));

        List<FeedItemResponse> items = service.getFeedByIds(List.of(41L, 42L), null, KnowPostFeedService.FeedVisibilityScope.PUBLIC);

        assertThat(items).extracting(FeedItemResponse::id).containsExactly("42");
    }

    @Test
    void feedPageResponseDefaultsNextCursorToNull() {
        FeedPageResponse response = new FeedPageResponse(List.of(), 1, 20, false);

        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void followHydrationRequiresViewerToActuallyFollowAuthorForFollowersOnlyRows() {
        when(knowPostMapper.listFeedByIds(List.of(51L), null, true)).thenReturn(List.of());

        List<FeedItemResponse> items = service.getFeedByIds(List.of(51L), null, KnowPostFeedService.FeedVisibilityScope.FOLLOW);

        assertThat(items).isEmpty();
    }

    private KnowPostFeedRow feedRow(long id, String publishTime) {
        KnowPostFeedRow row = new KnowPostFeedRow();
        row.setId(id);
        row.setTitle("title-" + id);
        row.setDescription("desc-" + id);
        row.setTags("[]");
        row.setImgUrls("[]");
        row.setAuthorNickname("author");
        row.setPublishTime(Instant.parse(publishTime));
        row.setIsTop(false);
        return row;
    }
}
