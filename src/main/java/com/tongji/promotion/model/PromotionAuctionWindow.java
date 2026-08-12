package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 竞价窗口：某资源类型在一个时间窗内收单、排序、定价与出位的批次单位；窗口关闭时统一结算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionAuctionWindow {
    private long id;
    private PromotionResourceType resourceType;
    private Instant windowStartAt;
    private Instant windowEndAt;
    private int slotCount;
    private long reservePrice;
    private PromotionAuctionWindowStatus status;
    private Instant settledAt;
    private Instant createdAt;
    private Instant updatedAt;
}
