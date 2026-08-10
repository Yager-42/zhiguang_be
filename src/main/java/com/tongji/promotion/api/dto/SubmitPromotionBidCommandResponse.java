package com.tongji.promotion.api.dto;

import java.time.Instant;

/**
 * Redis 权威返回的最终竞价结果。
 */
public record SubmitPromotionBidCommandResponse(
        String commandId,
        String auctionWindowId,
        String status,
        boolean resultAvailable,
        String rejectionReason,
        String decisionId,
        Long decisionVersion,
        Long bidAmount,
        Instant decidedAt
) {
    public SubmitPromotionBidCommandResponse(String commandId, String auctionWindowId, String status,
                                             boolean resultAvailable, String rejectionReason) {
        this(commandId, auctionWindowId, status, resultAvailable, rejectionReason,
                null, null, null, null);
    }

    public SubmitPromotionBidCommandResponse(String commandId, String auctionWindowId, String status,
                                             boolean resultAvailable) {
        this(commandId, auctionWindowId, status, resultAvailable, null);
    }
}
