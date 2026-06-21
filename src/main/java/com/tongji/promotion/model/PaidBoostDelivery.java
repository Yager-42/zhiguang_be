package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * boost 投放事实：内容一次被 boost 规则实际送达后的可结算记录。
 * <p>唯一键 {@code (campaignId, deliveryBucketStartAt, viewerUserId)} 保证同一 viewer 在同一 bucket
 * 内重复看到同一活动内容只累计 {@code deliveryCount}，不重复新增计费事实。
 * {@code capturedAmount} 初始为 0，结算时按 {@code min(剩余预算, deliveryCount * unitPriceSnapshot)} 回填。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidBoostDelivery {
    private long id;
    private long campaignId;
    private PaidBoostChannel channel;
    private long postId;
    private long viewerUserId;
    private Instant deliveryBucketStartAt;
    private int deliveryCount;
    private long unitPriceSnapshot;
    private long capturedAmount;
    private String settleBusinessRef;
    private PaidBoostDeliveryStatus status;
    private Instant settledAt;
    private Instant createdAt;
    private Instant updatedAt;
}
