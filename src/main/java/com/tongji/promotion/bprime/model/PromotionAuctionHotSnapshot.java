package com.tongji.promotion.bprime.model;

import java.util.List;

/** Redis 中未结算拍卖窗口的版本、共享当前价、固定截止时间与排名快照。 */
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

    /** 拍卖价格规则；{@code capCents=null} 表示未启用一口价。 */
    public record AuctionRules(long stepCents, Long capCents, long reserveCents) {
    }

    public PromotionAuctionHotSnapshot(long decisionVersion, List<PromotionRankingItem> ranking) {
        this(decisionVersion, ranking, 0L, "OPEN", "", 0L, 0L,
                new AuctionRules(0L, null, 0L));
    }
}
