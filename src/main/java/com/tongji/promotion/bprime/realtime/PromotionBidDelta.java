package com.tongji.promotion.bprime.realtime;

/**
 * 可由客户端合并进本地排名的单个 campaign 最新出价。
 *
 * <p>Snowflake ID 使用字符串传输，避免 JavaScript 数值精度丢失。</p>
 *
 * @since 2026-08-09
 */
public record PromotionBidDelta(
        String campaignId,
        String bidderUserId,
        String postId,
        long bidAmount
) {
}
