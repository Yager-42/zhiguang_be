package com.tongji.promotion.api.dto;

public record SubmitPromotionBidCommandResponse(
        String commandId,
        String auctionWindowId,
        String status,
        boolean resultAvailable,
        String rejectionReason
) {
    public SubmitPromotionBidCommandResponse(String commandId, String auctionWindowId, String status,
                                             boolean resultAvailable) {
        this(commandId, auctionWindowId, status, resultAvailable, null);
    }
}
