package com.tongji.promotion.api.dto;

import com.tongji.promotion.model.PromotionCampaign;

import java.time.Instant;

/** 推广活动响应。 */
public record PromotionCampaignResponse(
        String id,
        String postId,
        String resourceType,
        String status,
        Instant startAt,
        Instant endAt
) {
    public static PromotionCampaignResponse from(PromotionCampaign campaign) {
        return new PromotionCampaignResponse(
                String.valueOf(campaign.getId()),
                String.valueOf(campaign.getPostId()),
                campaign.getResourceType().placement(),
                campaign.getStatus().name(),
                campaign.getStartAt(),
                campaign.getEndAt()
        );
    }
}
