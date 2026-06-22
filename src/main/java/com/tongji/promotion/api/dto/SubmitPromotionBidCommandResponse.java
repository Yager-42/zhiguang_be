package com.tongji.promotion.api.dto;

public record SubmitPromotionBidCommandResponse(
        String commandId,
        String auctionWindowId,
        String status,
        boolean resultAvailable
) {}
