package com.tongji.promotion.service;

import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.recommendation.RecommendationCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaidBoostRankingServiceTest {

    private PaidBoostRankingService service;
    private final PaidBoostProperties properties = new PaidBoostProperties();

    @BeforeEach
    void setUp() {
        service = new PaidBoostRankingService(properties);
    }

    @Test
    void reranksByOrganicScorePlusBoostEffect() {
        List<RecommendationCandidate> ranked = service.rankRecommendationCandidates(
                List.of(
                        new RecommendationCandidate(201L, "gorse", 90.0),
                        new RecommendationCandidate(202L, "gorse", 95.0)
                ),
                Map.of(
                        201L, campaign(1L, 20L),
                        202L, campaign(2L, 1L)
                )
        );
        // 201: 90 + min(20, 50) = 110；202: 95 + min(1, 50) = 96
        assertThat(ranked).extracting(RecommendationCandidate::contentId).containsExactly(201L, 202L);
    }

    @Test
    void boostEffectCappedAtRecommendationMaxEffect() {
        properties.setRecommendationMaxBoostEffect(50L);
        List<RecommendationCandidate> ranked = service.rankRecommendationCandidates(
                List.of(
                        new RecommendationCandidate(201L, "gorse", 10.0),
                        new RecommendationCandidate(202L, "gorse", 10.0)
                ),
                Map.of(201L, campaign(1L, 999L))
        );
        // 201: 10 + min(999, 50) = 60；202: 10 + 0 = 10
        assertThat(ranked).extracting(RecommendationCandidate::contentId).containsExactly(201L, 202L);
    }

    @Test
    void noBoostsReturnsInputUnchanged() {
        List<RecommendationCandidate> input = List.of(
                new RecommendationCandidate(301L, "gorse", 5.0),
                new RecommendationCandidate(302L, "gorse", 50.0)
        );
        assertThat(service.rankRecommendationCandidates(input, Map.of())).isSameAs(input);
    }

    private PaidBoostCampaign campaign(long id, long boostValue) {
        return PaidBoostCampaign.builder()
                .id(id).creatorUserId(42L).postId(1000L + id).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .bidAmount(boostValue).boostValue(boostValue).unitPrice(2L).budgetTotal(100L).budgetConsumed(0L)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z"))
                .endAt(Instant.parse("2026-06-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
    }
}
