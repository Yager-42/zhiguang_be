package com.tongji.promotion.settlement;

import java.time.Instant;

public record PromotionAuctionTerminalInput(
        long auctionWindowId,
        Kind kind,
        Long winnerCampaignId,
        Long winningAmount,
        Instant decidedAt
) {
    public enum Kind {
        SOLD,
        NO_BID
    }
}
