package com.tongji.promotion.bprime.realtime;

import com.tongji.promotion.bprime.model.PromotionRankingItem;

import java.time.Instant;
import java.util.List;

public record PromotionAuctionRealtimeEvent(
        String eventId,
        String eventType,
        long auctionWindowId,
        String decisionId,
        long decisionVersion,
        long eventVersion,
        String windowStatus,
        List<PromotionRankingItem> ranking,
        Instant occurredAt
) {
    public static final String RANKING_UPDATED = "RANKING_UPDATED";
    public static final String WINDOW_CLOSED = "WINDOW_CLOSED";
}
