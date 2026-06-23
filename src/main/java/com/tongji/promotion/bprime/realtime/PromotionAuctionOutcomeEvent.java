package com.tongji.promotion.bprime.realtime;

import java.time.Instant;

public record PromotionAuctionOutcomeEvent(
        String eventId,
        String eventType,
        long auctionWindowId,
        long bidderUserId,
        String commandId,
        String decisionId,
        long decisionVersion,
        long bidAmount,
        String rejectionReason,
        Instant occurredAt
) {
    public static final String BID_CONFIRMED = "BID_CONFIRMED";
    public static final String BID_REJECTED = "BID_REJECTED";
}
