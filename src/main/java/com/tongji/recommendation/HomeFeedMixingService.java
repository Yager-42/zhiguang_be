package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.recommendation.feed.FollowFeedService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HomeFeedMixingService {

    private static final int TARGET_SIZE = 20;
    private static final int RECOMMENDATION_CANDIDATE_LIMIT = TARGET_SIZE * 2;
    private static final int PROMOTED_LIMIT = 1;

    private final FollowFeedService followFeedService;
    private final RecommendationEngine recommendationEngine;
    private final KnowPostMapper knowPostMapper;
    private final KnowPostFeedService knowPostFeedService;
    private final PromotionAllocationService promotionAllocationService;

    public HomeFeedMixingService(FollowFeedService followFeedService,
                                 RecommendationEngine recommendationEngine,
                                 KnowPostMapper knowPostMapper,
                                 KnowPostFeedService knowPostFeedService,
                                 PromotionAllocationService promotionAllocationService) {
        this.followFeedService = followFeedService;
        this.recommendationEngine = recommendationEngine;
        this.knowPostMapper = knowPostMapper;
        this.knowPostFeedService = knowPostFeedService;
        this.promotionAllocationService = promotionAllocationService;
    }

    public FeedPageResponse getHomeFeed(long userId) {
        List<FeedItemResponse> items = new ArrayList<>(TARGET_SIZE);
        Set<Long> seen = new LinkedHashSet<>();
        // 商业位先占坑：每次响应最多插 1 条 feed_top_slot，再由 organic 补位至 TARGET_SIZE
        appendPromoted(items, seen, userId, PROMOTED_LIMIT);
        String followCursor = null;
        do {
            int need = TARGET_SIZE - items.size();
            var followPage = followFeedService.getTimeline(userId, followCursor, TARGET_SIZE);
            List<Long> followHydrationIds = dedupe(
                    followPage.items().stream()
                            .map(item -> item.contentId())
                            .toList(),
                    seen
            );
            items.addAll(limit(
                    knowPostFeedService.getFeedByIds(followHydrationIds, userId, KnowPostFeedService.FeedVisibilityScope.FOLLOW),
                    need
            ));
            followCursor = followPage.nextCursor();
        } while (items.size() < TARGET_SIZE && followCursor != null);

        List<Long> recommendationIds = recommendationEngine.recommend(userId, RECOMMENDATION_CANDIDATE_LIMIT).stream()
                .map(RecommendationCandidate::contentId)
                .toList();
        List<Long> recommendationPool = dedupe(recommendationIds, seen);
        int recommendationIndex = 0;
        int hotOffset = 0;
        while (items.size() < TARGET_SIZE && recommendationIndex < recommendationPool.size()) {
            int need = TARGET_SIZE - items.size();
            int recommendationEnd = Math.min(recommendationIndex + need, recommendationPool.size());
            List<Long> recommendationBatch = recommendationPool.subList(recommendationIndex, recommendationEnd);
            recommendationIndex = recommendationEnd;
            items.addAll(limit(
                    knowPostFeedService.getFeedByIds(recommendationBatch, userId, KnowPostFeedService.FeedVisibilityScope.PUBLIC),
                    need
            ));
        }

        while (items.size() < TARGET_SIZE) {
            int need = TARGET_SIZE - items.size();
            List<Long> rawHotPage = knowPostMapper.listFeedPublicIds(TARGET_SIZE, hotOffset);
            hotOffset += TARGET_SIZE;
            if (rawHotPage == null || rawHotPage.isEmpty()) {
                break;
            }
            List<Long> hotBatch = dedupe(rawHotPage, seen);
            if (hotBatch.isEmpty()) {
                continue;
            }
            items.addAll(limit(
                    knowPostFeedService.getFeedByIds(hotBatch, userId, KnowPostFeedService.FeedVisibilityScope.PUBLIC),
                    need
            ));
        }
        return new FeedPageResponse(limit(items, TARGET_SIZE), 1, TARGET_SIZE, false);
    }

    /**
     * 插入 feed 商业位：取 active allocation 前 {@code limit} 条、去重占坑、hydrate 帖子并标记 promoted。
     * 找不到 active allocation 时直接 organic fallback（不插）。
     */
    private void appendPromoted(List<FeedItemResponse> items, Set<Long> seen, long userId, int limit) {
        List<PromotionAllocationView> allocations = promotionAllocationService.getActiveFeedAllocation();
        if (allocations == null || allocations.isEmpty()) {
            return;
        }
        List<Long> ids = allocations.stream()
                .limit(limit)
                .map(PromotionAllocationView::postIdAsLong)
                .filter(seen::add)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, PromotionAllocationView> byId = allocations.stream()
                .collect(Collectors.toMap(PromotionAllocationView::postIdAsLong, Function.identity(), (left, right) -> left));
        for (FeedItemResponse item : knowPostFeedService.getFeedByIds(ids, userId, KnowPostFeedService.FeedVisibilityScope.PUBLIC)) {
            PromotionAllocationView allocation = byId.get(Long.parseLong(item.id()));
            if (allocation != null) {
                items.add(item.withPromotion(allocation.placementType(), allocation.promotionCampaignId(),
                        allocation.auctionWindowId()));
            }
        }
    }

    private List<Long> dedupe(List<Long> ids, Set<Long> seen) {
        List<Long> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (Long id : ids) {
            if (id != null && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private <T> List<T> limit(List<T> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return List.of();
        }
        return items.size() <= limit ? items : new ArrayList<>(items.subList(0, limit));
    }
}
