package com.tongji.promotion.bprime.model;

import java.time.Instant;
import java.util.List;

public record PromotionAuctionDecision(
        String decisionId,
        String commandId,
        String requestHash,
        long auctionWindowId,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        String decisionType,
        boolean accepted,
        String rejectionReason,
        long bidAmount,
        List<PromotionRankingItem> ranking,
        List<PromotionWalletEffect> walletEffects,
        Instant decidedAt
) {}
