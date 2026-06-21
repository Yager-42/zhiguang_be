package com.tongji.promotion.api.dto;

import com.tongji.promotion.model.PaidBoostDelivery;

import java.time.Instant;

/** boost 投放明细响应：单条 delivery 事实的聚合视图（同 bucket 内已去重）。 */
public record PaidBoostDeliverySummaryResponse(
        String id,
        String postId,
        String viewerUserId,
        Instant deliveryBucketStartAt,
        int deliveryCount,
        long unitPriceSnapshot,
        long capturedAmount,
        String status,
        Instant settledAt
) {
    public static PaidBoostDeliverySummaryResponse from(PaidBoostDelivery delivery) {
        return new PaidBoostDeliverySummaryResponse(
                String.valueOf(delivery.getId()),
                String.valueOf(delivery.getPostId()),
                String.valueOf(delivery.getViewerUserId()),
                delivery.getDeliveryBucketStartAt(),
                delivery.getDeliveryCount(),
                delivery.getUnitPriceSnapshot(),
                delivery.getCapturedAmount(),
                delivery.getStatus().name(),
                delivery.getSettledAt()
        );
    }
}
