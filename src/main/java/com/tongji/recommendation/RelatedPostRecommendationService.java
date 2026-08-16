package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RelatedPostRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RelatedPostRecommendationService.class);
    private static final int DEFAULT_SIZE = 4;
    private static final int MAX_SIZE = 12;
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final KnowPostMapper knowPostMapper;
    private final KnowPostFeedService feedService;

    public RelatedPostRecommendationService(GorseClient gorseClient,
                                            GorseProperties properties,
                                            KnowPostMapper knowPostMapper,
                                            KnowPostFeedService feedService) {
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.knowPostMapper = knowPostMapper;
        this.feedService = feedService;
    }

    /**
     * 查询标签相似的公开知文；Gorse 关闭或不可用时按最新公开内容兜底。
     */
    public FeedPageResponse getRelated(long postId, int requestedSize, Long currentUserId) {
        int safeSize = requestedSize <= 0 ? DEFAULT_SIZE : Math.min(requestedSize, MAX_SIZE);
        int candidateCount = safeSize * CANDIDATE_MULTIPLIER;
        Set<Long> candidateIds = new LinkedHashSet<>(candidateCount);

        if (properties.isEnabled()) {
            try {
                addGorseCandidates(candidateIds, gorseClient.related(postId, candidateCount), postId);
            } catch (RestClientException exception) {
                log.warn("related post gorse fallback postId={} reason={}", postId, exception.getMessage());
            }
        }

        List<Long> hotIds = knowPostMapper.listFeedPublicIds(candidateCount + 1, 0);
        if (hotIds != null) {
            hotIds.stream()
                    .filter(id -> id != null && id != postId)
                    .forEach(candidateIds::add);
        }

        List<FeedItemResponse> hydrated = feedService.getFeedByIds(
                new ArrayList<>(candidateIds),
                currentUserId,
                KnowPostFeedService.FeedVisibilityScope.PUBLIC
        );
        if (hydrated == null || hydrated.isEmpty()) {
            return new FeedPageResponse(List.of(), 1, safeSize, false);
        }
        List<FeedItemResponse> items = hydrated.size() > safeSize
                ? hydrated.subList(0, safeSize)
                : hydrated;
        return new FeedPageResponse(List.copyOf(items), 1, safeSize, false);
    }

    private void addGorseCandidates(Set<Long> target, List<String> rawIds, long excludedPostId) {
        if (rawIds == null) {
            return;
        }
        for (String rawId : rawIds) {
            try {
                long id = Long.parseLong(rawId);
                if (id != excludedPostId) {
                    target.add(id);
                }
            } catch (NumberFormatException ignored) {
                log.debug("ignore malformed gorse item id={}", rawId);
            }
        }
    }
}
