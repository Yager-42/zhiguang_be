package com.tongji.promotion.bprime.model;

import java.util.List;

/** Redis 中未结算拍卖窗口的版本、共享当前价与排名快照（Go RoomSnapshot 同构，英式升价加性字段）。 */
public record PromotionAuctionHotSnapshot(
        long decisionVersion,
        List<PromotionRankingItem> ranking,
        long currentPriceCents,
        String status,
        String winnerCampaignId,
        long windowEndAtEpochMs,
        long bidCount,
        AuctionRules rules
) {

    /** 拍卖规则（Go RoomSnapshotRules 同构：capCents=null 表示无一口价）。 */
    public record AuctionRules(
            long stepCents,
            Long capCents,
            long reserveCents,
            int maxExtensions,
            long antiSnipeWindowMs
    ) {
    }

    public PromotionAuctionHotSnapshot(long decisionVersion, List<PromotionRankingItem> ranking) {
        this(decisionVersion, ranking, 0L, "OPEN", "", 0L, 0L,
                new AuctionRules(0L, null, 0L, 0, 0L));
    }
}
