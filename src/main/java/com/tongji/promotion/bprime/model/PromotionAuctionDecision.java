package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PromotionAuctionDecision(
        String decisionId,
        String commandId,
        String requestHash,
        long auctionWindowId,
        long decisionVersion,
        long previousVersion,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        @JsonProperty("type") @JsonAlias("decisionType") String type,
        boolean accepted,
        String rejectionReason,
        long bidAmount,
        List<PromotionRankingItem> ranking,
        List<PromotionWalletEffect> walletEffects,
        Map<String, Object> payload,
        Instant decidedAt
) {
    public PromotionAuctionDecision(String decisionId,
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
                                    Instant decidedAt) {
        this(decisionId, commandId, requestHash, auctionWindowId, 1L, 0L, campaignId, bidderUserId, postId,
                resourceType, decisionType, accepted, rejectionReason, bidAmount, ranking, walletEffects, Map.of(),
                decidedAt);
    }

    public String decisionType() {
        return type;
    }
}
