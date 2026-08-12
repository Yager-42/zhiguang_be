package com.tongji.promotion.bprime.model;

/**
 * 批量裁决中的单项结果。
 *
 * @since 2026-08-12
 */
public record PromotionAuctionBatchItemResult(
        int inputIndex,
        PromotionAuctionBatchOutcome outcome,
        PromotionAuctionDecision decision
) {
}
