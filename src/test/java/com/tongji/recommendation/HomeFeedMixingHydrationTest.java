package com.tongji.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.FeedPageCounterState;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostFeedRow;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.impl.KnowPostFeedServiceImpl;
import com.tongji.promotion.api.dto.PromotionAllocationView;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        List<FeedItemResponse> items = service.getFeedByIds(List.of(11L, 12L), null, KnowPostFeedService.FeedVisibilityScope.PUBLIC);

        assertThat(items).extracting(FeedItemResponse::id).containsExactly("11", "12");
    }

    @Test
    void sourceAwareHydrationUsesFollowVisibilityForFollowScope() {
        when(knowPostMapper.listFeedByIds(List.of(21L), 42L, true)).thenReturn(List.of(feedRow(21L, "2026-06-18T10:15:30Z")));
        when(counterService.getFeedPageStateBatch("knowpost", List.of("21"), 42L, List.of("like", "fav")))
                .thenReturn(Map.of("21", new FeedPageCounterState(Map.of("like", 7L, "fav", 3L), true, false)));

        List<FeedItemResponse> items = service.getFeedByIds(List.of(21L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW);

        assertThat(items).extracting(FeedItemResponse::id).containsExactly("21");
        assertThat(items.getFirst().likeCount()).isEqualTo(7L);
        assertThat(items.getFirst().favoriteCount()).isEqualTo(3L);
        assertThat(items.getFirst().liked()).isTrue();
        assertThat(items.getFirst().faved()).isFalse();
        verify(counterService, times(1)).getFeedPageStateBatch(
                "knowpost", List.of("21"), 42L, List.of("like", "fav"));
        verify(counterService, never()).getCounts(anyString(), anyString(), anyList());
        verify(counterService, never()).isLiked(anyString(), anyString(), eq(42L));
        verify(counterService, never()).isFaved(anyString(), anyString(), eq(42L));
    }

    @Test
    void publicHydrationExcludesFollowersOnlyRowsWhileFollowHydrationAllowsThem() {
        when(knowPostMapper.listFeedByIds(List.of(31L), 42L, false)).thenReturn(List.of());
        when(knowPostMapper.listFeedByIds(List.of(31L), 42L, true)).thenReturn(List.of(feedRow(31L, "2026-06-18T10:15:30Z")));
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

    private FeedItemResponse feedItem(String id) {
        return FeedItemResponse.organic(id, "title-" + id, "desc-" + id, null, List.of(),
                null, "author", null, 0L, 0L, false, false, false);
    }

    @Test
    void publicFeedPageOneInsertsSinglePromotedSlot() {
        // cache 命中路径：organic 页 [202, 203]，page=1 时在前插入 1 条 promoted
        when(feedPublicCache.getIfPresent(anyString())).thenReturn(
                new FeedPageResponse(List.of(feedItem("202"), feedItem("203")), 1, 2, false));
        when(promotionAllocationService.getActiveFeedAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "feed_top_slot", "301", "401")));
        when(knowPostMapper.listFeedByIds(List.of(201L), null, false))
                .thenReturn(List.of(feedRow(201L, "2026-06-18T10:15:30Z")));

        FeedPageResponse response = service.getPublicFeed(1, 2, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.items().get(0).commercial()).isTrue();
        assertThat(response.items().get(0).placementType()).isEqualTo("feed_top_slot");
        assertThat(response.items().get(1).commercial()).isFalse();
    }

    @Test
    void publicFeedPageBeyondOneDoesNotInsertPromotedSlot() {
        when(feedPublicCache.getIfPresent(anyString())).thenReturn(
                new FeedPageResponse(List.of(feedItem("301"), feedItem("302")), 2, 2, false));

        FeedPageResponse response = service.getPublicFeed(2, 2, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("301", "302");
        assertThat(response.items()).allSatisfy(item -> assertThat(item.commercial()).isFalse());
        verify(promotionAllocationService, never()).getActiveFeedAllocation();
        verify(redisTemplate, never()).getExpire(anyString());
    }

    @Test
    void publicFeedDedupKeepsPageSizeWhenPromotedOverlapsOrganic() {
        // promoted 201 与 organic 201 重叠：去重后页大小仍为 2（promoted 201 + organic 202）
        when(feedPublicCache.getIfPresent(anyString())).thenReturn(
                new FeedPageResponse(List.of(feedItem("201"), feedItem("202")), 1, 2, false));
        when(promotionAllocationService.getActiveFeedAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "feed_top_slot", "301", "401")));
        when(knowPostMapper.listFeedByIds(List.of(201L), null, false))
                .thenReturn(List.of(feedRow(201L, "2026-06-18T10:15:30Z")));

        FeedPageResponse response = service.getPublicFeed(1, 2, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).commercial()).isTrue();
        assertThat(response.items().get(1).commercial()).isFalse();
    }
}
