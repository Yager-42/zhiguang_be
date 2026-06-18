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
        return candidateIds.stream()
                .map(Long::parseLong)
                .map(id -> new RecommendationCandidate(id, "gorse"))
                .toList();
    }

    private List<RecommendationCandidate> hotFallback(int count) {
        return knowPostMapper.listFeedPublicIds(count, 0).stream()
                .filter(Objects::nonNull)
                .map(id -> new RecommendationCandidate(id, "hot"))
                .toList();
    }
}
