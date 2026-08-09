package com.tongji.promotion.bprime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionAuctionCommandRecord {
    private long id;
    private String commandId;
    private String idempotencyKey;
    private String requestHash;
    private long auctionWindowId;
    private long campaignId;
    private long bidderUserId;
    private long postId;
    private String resourceType;
    private long bidAmount;
    private long reservePrice;
    private String windowStatus;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public PromotionAuctionCommand toCommand() {
        return new PromotionAuctionCommand(commandId, idempotencyKey, requestHash, auctionWindowId,
                campaignId, bidderUserId, postId, resourceType, bidAmount, reservePrice, windowStatus,
                "BID", createdAt);
    }
}
