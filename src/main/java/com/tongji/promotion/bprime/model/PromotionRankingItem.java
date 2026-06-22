package com.tongji.promotion.bprime.model;

public record PromotionRankingItem(
        long campaignId,
        long bidderUserId,
        long postId,
        long bidAmount,
        int rank
) {}
