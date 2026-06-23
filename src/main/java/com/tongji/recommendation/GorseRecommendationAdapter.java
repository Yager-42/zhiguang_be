package com.tongji.recommendation;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GorseRecommendationAdapter implements RecommendationEngine {

    private final KnowPostMapper knowPostMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;

    @Autowired
    public GorseRecommendationAdapter(KnowPostMapper knowPostMapper, GorseProperties properties) {
        this(knowPostMapper, new GorseClient(new RestTemplate(), properties), properties);
    }

    GorseRecommendationAdapter(KnowPostMapper knowPostMapper, GorseClient gorseClient, GorseProperties properties) {
        this.knowPostMapper = Objects.requireNonNull(knowPostMapper, "knowPostMapper");
        this.gorseClient = Objects.requireNonNull(gorseClient, "gorseClient");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public List<RecommendationCandidate> recommend(long userId, int count) {
        if (count <= 0) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            return hotFallback(count);
        }
        List<String> candidateIds;
        try {
            candidateIds = gorseClient.recommend(userId, count);
        } catch (RestClientException ex) {
            return hotFallback(count);
        }
        return scoreByPosition(candidateIds.stream()
                .map(Long::parseLong)
                .toList(), count, "gorse");
    }

    private List<RecommendationCandidate> hotFallback(int count) {
        return scoreByPosition(knowPostMapper.listFeedPublicIds(count, 0).stream()
                .filter(Objects::nonNull)
                .toList(), count, "hot");
    }

    /**
     * 给候选按原始顺序赋予单调递减的 organic 基线分（首个 = count，依次 -1）。
     * <p>递减而非平坦：保证自然候选按来源相关度顺序排列，
     * 不会因同分被 contentId 重排破坏 gorse/hot 的原始召回顺序。</p>
     */
    private List<RecommendationCandidate> scoreByPosition(List<Long> ids, int count, String source) {
        AtomicInteger rank = new AtomicInteger(count);
        return ids.stream()
                .map(id -> new RecommendationCandidate(id, source, rank.getAndDecrement()))
                .toList();
    }
}
