package com.tongji.promotion.bprime.model;

import java.util.List;

/** Redis 中未结算拍卖窗口的版本与排名快照。 */
public record PromotionAuctionHotSnapshot(long decisionVersion, List<PromotionRankingItem> ranking) {
}
