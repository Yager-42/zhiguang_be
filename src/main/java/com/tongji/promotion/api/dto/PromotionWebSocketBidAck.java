package com.tongji.promotion.api.dto;

import java.time.Instant;

/**
 * WebSocket 推广出价的最终私有响应。
 */
public record PromotionWebSocketBidAck(
        String idempotencyKey,
        String commandId,
        String auctionWindowId,
        String status,
        boolean resultAvailable,
        String rejectionReason,
        String decisionId,
        Long decisionVersion,
        Long bidAmount,
        Instant decidedAt,
        Long requiredAmount,
        Boolean leadingAtDecision,
        String winnerCampaignId,
        Long currentPriceCents
) {

    public PromotionWebSocketBidAck(String idempotencyKey, String commandId, String auctionWindowId,
                                    String status, boolean resultAvailable, String rejectionReason) {
        this(idempotencyKey, commandId, auctionWindowId, status, resultAvailable, rejectionReason,
                null, null, null, null, null, null, null, null);
    }

    public static PromotionWebSocketBidAck from(
            String idempotencyKey,
            SubmitPromotionBidCommandResponse response) {
        return new PromotionWebSocketBidAck(
                idempotencyKey,
                response.commandId(),
                response.auctionWindowId(),
                response.status(),
                response.resultAvailable(),
                response.rejectionReason(),
                response.decisionId(),
                response.decisionVersion(),
                response.bidAmount(),
                response.decidedAt(),
                response.requiredAmount(),
                response.leadingAtDecision(),
                response.winnerCampaignId(),
                response.currentPriceCents());
    }

    public static PromotionWebSocketBidAck rejected(String idempotencyKey, String rejectionReason) {
        return new PromotionWebSocketBidAck(idempotencyKey, null, null, "REJECTED", true,
                rejectionReason, null, null, null, null, null, null, null, null);
    }

    public static PromotionWebSocketBidAck unavailable(String idempotencyKey, String rejectionReason) {
        return new PromotionWebSocketBidAck(idempotencyKey, null, null, "UNAVAILABLE", false,
                rejectionReason, null, null, null, null, null, null, null, null);
    }
}
