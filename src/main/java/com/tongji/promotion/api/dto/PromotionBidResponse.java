package com.tongji.promotion.api.dto;

import com.tongji.promotion.model.PromotionBid;

/** 推广出价响应。中标结算后携带成交价与位号。 */
public record PromotionBidResponse(
        String id,
        String campaignId,
        String auctionWindowId,
        long bidAmount,
        String status,
        Long clearingPrice,
        Integer slotIndex
) {
    public static PromotionBidResponse from(PromotionBid bid) {
        return new PromotionBidResponse(
                String.valueOf(bid.getId()),
                String.valueOf(bid.getCampaignId()),
                String.valueOf(bid.getAuctionWindowId()),
                bid.getBidAmount(),
                bid.getStatus().name(),
                bid.getClearingPrice(),
                bid.getSlotIndex()
        );
    }
}
