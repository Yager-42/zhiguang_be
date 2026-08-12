package com.tongji.promotion.bprime.model;

import java.time.Instant;

/** Redis 中的低成本 WebSocket 收单路由；由保证金授权边界创建。 */
public record PromotionBidRoute(
        long campaignId,
        long bidderUserId,
        long postId,
        long auctionWindowId,
        String resourceType,
        long reservePrice,
        long authorizedAmount,
        String windowStatus,
        Instant windowEndAt,
        int slotCount,
        long incrementCents,
        long capPriceCents,
        long extendWindowSec,
        long extendSec,
        int maxExtensions
) {
    public PromotionBidRoute(long campaignId, long bidderUserId, long postId, long auctionWindowId,
                             String resourceType, long reservePrice, long authorizedAmount,
                             String windowStatus, Instant windowEndAt) {
        this(campaignId, bidderUserId, postId, auctionWindowId, resourceType, reservePrice, authorizedAmount,
                windowStatus, windowEndAt, 1, 0L, 0L, 0L, 0L, 0);
    }

    public PromotionBidRoute(long campaignId, long bidderUserId, long postId, long auctionWindowId,
                             String resourceType, long reservePrice, long authorizedAmount,
                             String windowStatus, Instant windowEndAt, int slotCount) {
        this(campaignId, bidderUserId, postId, auctionWindowId, resourceType, reservePrice, authorizedAmount,
                windowStatus, windowEndAt, slotCount, 0L, 0L, 0L, 0L, 0);
    }
}
