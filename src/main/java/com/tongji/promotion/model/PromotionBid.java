package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 推广出价：某活动在某窗口的单条出价，接单即冻结申报价；结算后写入成交价与位号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionBid {
    private long id;
    private long campaignId;
    private long auctionWindowId;
    private long bidderUserId;
    private long bidAmount;
    private String walletBusinessRef;
    private PromotionBidStatus status;
    private Long clearingPrice;
    private Integer slotIndex;
    private Instant createdAt;
    private Instant updatedAt;
}
