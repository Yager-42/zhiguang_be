package com.tongji.promotion.bprime.model;

import java.time.Instant;
import java.util.List;

public record PromotionAuctionSnapshot(
        long auctionWindowId,
        String status,
        List<PromotionRankingItem> ranking,
        Instant serverTime,
        long decisionVersion
) {
    public PromotionAuctionSnapshot(long auctionWindowId, String status, List<PromotionRankingItem> ranking,
                                    Instant serverTime) {
        this(auctionWindowId, status, ranking, serverTime, 0L);
    }
}
