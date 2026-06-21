package com.tongji.promotion.api.dto;

import com.tongji.promotion.model.PaidBoostCampaign;

import java.time.Instant;

/**
 * boost 活动响应：含预算消耗进度与有效 boost 值，不含拍卖字段（无 winner/window/clearing price）。
 */
public record PaidBoostCampaignResponse(
        String id,
        String postId,
        String channel,
        long bidAmount,
        long effectiveBoostValue,
        long unitPrice,
        long budgetTotal,
        long budgetConsumed,
        String status,
        Instant startAt,
        Instant endAt,
        Instant closedAt
) {
    public static PaidBoostCampaignResponse from(PaidBoostCampaign campaign) {
        return new PaidBoostCampaignResponse(
                String.valueOf(campaign.getId()),
                String.valueOf(campaign.getPostId()),
                campaign.getChannel().wireValue(),
                campaign.getBidAmount(),
                campaign.getBoostValue(),
                campaign.getUnitPrice(),
                campaign.getBudgetTotal(),
                campaign.getBudgetConsumed(),
                campaign.getStatus().name(),
                campaign.getStartAt(),
                campaign.getEndAt(),
                campaign.getClosedAt()
        );
    }
}
