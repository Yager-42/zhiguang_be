package com.tongji.promotion.bprime.model;

/**
 * 竞价排名项。campaignId/bidderUserId/postId 用 String 序列化（snowflake 64 位 >2^53，防 JS 精度丢失）。
 * bidAmount 是积分类小额整数（保留价 1L），保留 long；rank 是名次，保留 int。
 */
public record PromotionRankingItem(
        String campaignId,
        String bidderUserId,
        String postId,
        long bidAmount,
        int rank
) {}
