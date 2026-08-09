package com.tongji.promotion.api.dto;

/** 推广竞价保证金授权结果；Snowflake ID 使用 String 避免 JavaScript 精度丢失。 */
public record PromotionBidEscrowAuthorizationResponse(
        String auctionWindowId,
        String campaignId,
        long authorizedAmount,
        long currentHold,
        String status
) {
}
