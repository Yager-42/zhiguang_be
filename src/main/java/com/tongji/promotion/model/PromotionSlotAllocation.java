package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 位分配：窗口结算后的占位结果，驱动 feed/search 商业位读路径。
 * 有效期 {@code [allocationStartAt, allocationEndAt)} = 结算窗口结束后的下一个窗口周期。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionSlotAllocation {
    private long id;
    private long auctionWindowId;
    private PromotionResourceType resourceType;
    private int slotIndex;
    private long campaignId;
    private long postId;
    private long bidderUserId;
    private long clearingPrice;
    private Instant allocationStartAt;
    private Instant allocationEndAt;
    private Instant createdAt;
}
