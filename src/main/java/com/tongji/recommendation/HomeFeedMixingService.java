package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.feed.FollowFeedService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class HomeFeedMixingService {

    private static final int TARGET_SIZE = 20;
    private static final int RECOMMENDATION_CANDIDATE_LIMIT = TARGET_SIZE * 2;

    private final FollowFeedService followFeedService;
    private final RecommendationEngine recommendationEngine;
    private final KnowPostMapper knowPostMapper;
    private final KnowPostFeedService knowPostFeedService;

    public HomeFeedMixingService(FollowFeedService followFeedService,
                                 RecommendationEngine recommendationEngine,
                                 KnowPostMapper knowPostMapper,
                                 KnowPostFeedService knowPostFeedService) {
        this.followFeedService = followFeedService;
        this.recommendationEngine = recommendationEngine;
        this.knowPostMapper = knowPostMapper;
        this.knowPostFeedService = knowPostFeedService;
    }

    public FeedPageResponse getHomeFeed(long userId) {
        List<FeedItemResponse> items = new ArrayList<>(TARGET_SIZE);
        Set<Long> seen = new LinkedHashSet<>();
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
