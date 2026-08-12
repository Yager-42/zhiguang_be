package com.tongji.promotion.bprime.model;

import java.util.List;

/**
 * Redis 批量裁决返回值；结果通过输入下标与调用方 Future 对齐。
 *
 * @since 2026-08-12
 */
public record PromotionAuctionBatchResult(
        List<PromotionAuctionBatchItemResult> items,
        long committedPriceCents,
        String winnerCommandId,
        long winnerCampaignId,
        String status,
        long actualEndAtEpochMs,
        long decisionVersion
) {
    public PromotionAuctionBatchResult {
        items = List.copyOf(items);
    }
}
