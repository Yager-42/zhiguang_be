package com.tongji.promotion.bprime.model;

/**
 * 携带一个已校验的 Redis Stream 决策及其源位置。
 *
 * @since 2026-08-08
 */
public record PromotionDecisionProjectionItem(
        PromotionAuctionDecision decision,
        String streamId
) {}
