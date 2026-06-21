package com.tongji.promotion.service;

import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.recommendation.RecommendationCandidate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 推荐排序本地加权：{@code finalScore = organicScore + boostEffect}。
 * <p>boostEffect 取 {@code min(boostValue, recommendationMaxBoostEffect)}；仅 active、预算未耗尽、仍可见的候选
 * 会出现在传入的 boosts 映射里（由 {@link PaidBoostCacheService} 过滤），本服务只负责叠加与排序，
 * 不查询 DB、不改 Gorse/ES 协议。boost 不能绕过可见性与删除状态（由后续 hydrate 过滤保证）。</p>
 */
@Service
public class PaidBoostRankingService {

    private final PaidBoostProperties properties;

    public PaidBoostRankingService(PaidBoostProperties properties) {
        this.properties = properties;
    }

    public List<RecommendationCandidate> rankRecommendationCandidates(List<RecommendationCandidate> input,
                                                                      Map<Long, PaidBoostCampaign> boosts) {
        if (boosts == null || boosts.isEmpty()) {
            return input;
        }
        return input.stream()
                .sorted(Comparator
                        .comparingDouble((RecommendationCandidate c) -> c.organicScore() + effect(boosts.get(c.contentId())))
                        .reversed()
                        .thenComparingLong(RecommendationCandidate::contentId))
                .toList();
    }

    private double effect(PaidBoostCampaign campaign) {
        if (campaign == null) {
            return 0D;
        }
        return Math.min(campaign.getBoostValue(), properties.getRecommendationMaxBoostEffect());
    }
}
