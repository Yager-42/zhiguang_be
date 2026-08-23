package com.tongji.promotion.api.dto;

/** 当前系统竞价场次的报名结果。 */
public record PromotionAuctionEntryResponse(
        String auctionWindowId,
        PromotionCampaignResponse participation
) {
}
