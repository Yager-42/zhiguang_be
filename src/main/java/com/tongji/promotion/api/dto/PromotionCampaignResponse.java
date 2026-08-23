package com.tongji.promotion.api.dto;

import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignDetails;

import java.time.Instant;

/** 推广活动响应。 */
public record PromotionCampaignResponse(
        String id,
        String postId,
        String creatorUserId,
        String creatorNickname,
        String resourceType,
        String status,
        String participationOutcome,
        Instant recommendationStartAt,
        Instant recommendationEndAt,
        Instant startAt,
        Instant endAt,
        Instant createdAt
) {
    public static PromotionCampaignResponse from(PromotionCampaignDetails details) {
        PromotionCampaign campaign = details.campaign();
        return new PromotionCampaignResponse(
                String.valueOf(campaign.getId()),
                String.valueOf(campaign.getPostId()),
                String.valueOf(campaign.getCreatorUserId()),
                details.creatorNickname(),
                campaign.getResourceType().placement(),
                campaign.getStatus().name(),
                details.participationOutcome().name(),
                details.allocation() == null ? null : details.allocation().getAllocationStartAt(),
                details.allocation() == null ? null : details.allocation().getAllocationEndAt(),
                campaign.getStartAt(),
                campaign.getEndAt(),
                campaign.getCreatedAt()
        );
    }
}
