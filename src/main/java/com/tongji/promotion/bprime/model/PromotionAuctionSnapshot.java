package com.tongji.promotion.bprime.model;

import java.time.Instant;
import java.util.List;

/**
 * 竞价窗口快照。auctionWindowId 用 String 序列化（snowflake 64 位 >2^53，防 JS 精度丢失）。
 * decisionVersion 是窗口内单调递增计数（远小于 2^53），保留 long。
 * windowEndAt 用于前端显示固定截止倒计时；currentPriceCents/rules 为英式升价加性字段。
 */
public record PromotionAuctionSnapshot(
        String auctionWindowId,
        String status,
        List<PromotionRankingItem> ranking,
        Instant serverTime,
        long decisionVersion,
        Instant windowEndAt,
        long currentPriceCents,
        String winnerCampaignId,
        long bidCount,
        PromotionAuctionHotSnapshot.AuctionRules rules,
        Instant windowStartAt,
        String resourceType
) {
    public PromotionAuctionSnapshot(String auctionWindowId, String status, List<PromotionRankingItem> ranking,
                                    Instant serverTime, long decisionVersion) {
        this(auctionWindowId, status, ranking, serverTime, decisionVersion, null,
                0L, "", 0L, new PromotionAuctionHotSnapshot.AuctionRules(0L, null, 0L),
                null, null);
    }
}
