package com.tongji.promotion.bprime.model;

/**
 * 批量 Lua 与 Java 解码器之间的稳定结果码。
 *
 * @since 2026-08-12
 */
public enum PromotionAuctionBatchOutcome {
    ACCEPTED,
    REPLAYED_ACCEPTED,
    BID_NOT_HIGHER,
    ESCROW_INSUFFICIENT,
    IDEMPOTENCY_CONFLICT,
    WINDOW_CLOSED
}
