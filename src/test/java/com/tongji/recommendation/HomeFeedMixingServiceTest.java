package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.recommendation.feed.FollowFeedService;
import com.tongji.recommendation.feed.TimelineItem;
import com.tongji.recommendation.feed.TimelinePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeFeedMixingServiceTest {

    @Mock
    private FollowFeedService followFeedService;

    @Mock
    private RecommendationEngine recommendationEngine;

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private KnowPostFeedService knowPostFeedService;

    @Mock
    private PromotionAllocationService promotionAllocationService;

    private HomeFeedMixingService service;

    @BeforeEach
    void setUp() {
        service = new HomeFeedMixingService(followFeedService, recommendationEngine, knowPostMapper,
                knowPostFeedService, promotionAllocationService);
    }

    @Test
    void fillsFollowThenRecommendationThenHotFallback() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(101L, 10L, "2026-06-18T10:15:30Z"),
                timelineItem(102L, 10L, "2026-06-18T10:15:29Z")
        ), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
                new RecommendationCandidate(201L, "gorse"),
                new RecommendationCandidate(202L, "gorse")
        ));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(List.of(301L, 302L));
        when(knowPostFeedService.getFeedByIds(List.of(101L, 102L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(101L), feedItem(102L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    for (Long id : ids) {
                        out.add(feedItem(id));
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactly("101", "102", "201", "202", "301", "302");
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void deduplicatesBeforeHydrationAndKeepsFollowVisibility() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(501L, 10L, "2026-06-18T10:15:30Z")
        ), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
                new RecommendationCandidate(501L, "gorse"),
                new RecommendationCandidate(601L, "gorse")
        ));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(List.of(501L, 701L));
        when(knowPostFeedService.getFeedByIds(List.of(501L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(501L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    for (Long id : ids) {
                        out.add(feedItem(id));
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactly("501", "601", "701");
        verify(knowPostFeedService).getFeedByIds(List.of(501L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(knowPostFeedService).getFeedByIds(List.of(601L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
        verify(knowPostFeedService).getFeedByIds(List.of(701L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
    }

    @Test
    void refillsFromLaterSourcesAfterFilteredCandidatesDropOut() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(801L, 10L, "2026-06-18T10:15:30Z"),
                timelineItem(802L, 10L, "2026-06-18T10:15:29Z")
        ), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
                new RecommendationCandidate(901L, "gorse")
        ));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(List.of(1001L, 1002L));
        when(knowPostFeedService.getFeedByIds(List.of(801L, 802L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(802L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    if (ids.contains(1001L)) {
                        out.add(feedItem(1001L));
                    }
                    if (ids.contains(1002L)) {
                        out.add(feedItem(1002L));
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactly("802", "1001", "1002");
    }

    @Test
    void exhaustsFollowCursorPagesBeforeRecommendationAndHotFallback() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(1101L, 10L, "2026-06-18T10:15:30Z"),
                timelineItem(1102L, 10L, "2026-06-18T10:15:29Z")
        ), "cursor-1"));
        when(followFeedService.getTimeline(42L, "cursor-1", 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(1103L, 10L, "2026-06-18T10:15:28Z")
        ), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
                new RecommendationCandidate(1201L, "gorse")
        ));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(List.of(1301L));
        when(knowPostFeedService.getFeedByIds(List.of(1101L, 1102L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(1102L)));
        when(knowPostFeedService.getFeedByIds(List.of(1103L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(1103L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    for (Long id : ids) {
                        out.add(feedItem(id));
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactly("1102", "1103", "1201", "1301");
    }

    @Test
    void continuesPullingUntilTargetReachedAfterFilteringDropsWholeBatches() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(
                java.util.stream.LongStream.rangeClosed(1, 20)
                        .mapToObj(id -> timelineItem(id, 10L, "2026-06-18T10:15:30Z"))
                        .toList(),
                null
        ));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(java.util.stream.LongStream.rangeClosed(21, 40)
                .mapToObj(id -> new RecommendationCandidate(id, "gorse"))
                .toList());
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(java.util.stream.LongStream.rangeClosed(41, 60).boxed().toList());
        when(knowPostMapper.listFeedPublicIds(20, 20)).thenReturn(java.util.stream.LongStream.rangeClosed(61, 80).boxed().toList());
        when(knowPostFeedService.getFeedByIds(
                java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(),
                42L,
                KnowPostFeedService.FeedVisibilityScope.FOLLOW
        )).thenReturn(List.of(feedItem(1L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    if (ids.contains(21L)) {
                        out.add(feedItem(21L));
                    }
                    if (ids.contains(41L)) {
                        out.add(feedItem(41L));
                    }
                    if (ids.contains(61L)) {
                        out.addAll(java.util.stream.LongStream.rangeClosed(61, 77).mapToObj(this::feedItem).toList());
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).hasSize(20);
        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("1", "21", "41"),
                        java.util.stream.LongStream.rangeClosed(61, 77).mapToObj(String::valueOf)
                ).toList());
    }

    @Test
    void exhaustsRecommendationsBeforeUsingHotFallback() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(java.util.stream.LongStream.rangeClosed(21, 60)
                .mapToObj(id -> new RecommendationCandidate(id, "gorse"))
                .toList());
        when(knowPostFeedService.getFeedByIds(List.of(), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of());
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    if (ids.equals(java.util.stream.LongStream.rangeClosed(21, 40).boxed().toList())) {
                        out.add(feedItem(21L));
                    }
                    if (ids.equals(java.util.stream.LongStream.rangeClosed(41, 59).boxed().toList())) {
                        out.addAll(java.util.stream.LongStream.rangeClosed(41, 59).mapToObj(this::feedItem).toList());
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).hasSize(20);
        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("21"),
                        java.util.stream.LongStream.rangeClosed(41, 59).mapToObj(String::valueOf)
                ).toList());
    }

    @Test
    void continuesPastEmptyDedupedHotPageToLaterHotPages() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(1L, 10L, "2026-06-18T10:15:30Z")
        ), null));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
                new RecommendationCandidate(2L, "gorse")
        ));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(List.of(1L, 2L));
        when(knowPostMapper.listFeedPublicIds(20, 20)).thenReturn(List.of(3L, 4L));
        when(knowPostMapper.listFeedPublicIds(20, 40)).thenReturn(List.of());
        when(knowPostFeedService.getFeedByIds(List.of(1L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(1L)));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    List<FeedItemResponse> out = new ArrayList<>();
                    for (Long id : ids) {
                        if (id == 2L || id >= 3L) {
                            out.add(feedItem(id));
                        }
                    }
                    return out;
                });

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void keepsFixedTargetOfTwentyItems() {
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(
                java.util.stream.LongStream.rangeClosed(1, 20)
                        .mapToObj(id -> timelineItem(id, 10L, "2026-06-18T10:15:30Z"))
                        .toList(),
                null
        ));
        when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(new RecommendationCandidate(21L, "gorse")));
        when(knowPostFeedService.getFeedByIds(
                java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(),
                42L,
                KnowPostFeedService.FeedVisibilityScope.FOLLOW
        )).thenReturn(java.util.stream.LongStream.rangeClosed(1, 20).mapToObj(this::feedItem).toList());
        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).hasSize(20);
        assertThat(response.items()).extracting(FeedItemResponse::id)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList());
    }

    @Test
    void insertsPromotedFeedSlotBeforeOrganicAndDedupesPost() {
        PromotionAllocationView promoted = new PromotionAllocationView("201", "feed_top_slot", "301", "401");
        when(promotionAllocationService.getActiveFeedAllocation()).thenReturn(List.of(promoted));
        when(knowPostFeedService.getFeedByIds(List.of(201L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC))
                .thenReturn(List.of(feedItem(201L)));
        when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
                timelineItem(201L, 10L, "2026-06-18T10:15:30Z"),
                timelineItem(202L, 10L, "2026-06-18T10:15:29Z")
        ), null));
        when(knowPostFeedService.getFeedByIds(List.of(202L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem(202L)));

        FeedPageResponse response = service.getHomeFeed(42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.items().getFirst().commercial()).isTrue();
        assertThat(response.items().getFirst().promoted()).isTrue();
        assertThat(response.items().getFirst().placementType()).isEqualTo("feed_top_slot");
    }

    private TimelineItem timelineItem(long contentId, long authorId, String publishTs) {
        return new TimelineItem(contentId, authorId, Instant.parse(publishTs));
    }

    private FeedItemResponse feedItem(long id) {
        return FeedItemResponse.organic(
                String.valueOf(id),
                "title-" + id,
                "desc-" + id,
                null,
                List.of(),
                null,
                "author",
                null,
                0L,
                0L,
                false,
                false,
                false
        );
    }
}
