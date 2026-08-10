package com.tongji.promotion.bprime.model;

import lombok.Data;

/**
 * 单个竞价窗口的 Redis Stream 投影水位。
 *
 * @since 2026-08-08
 */
@Data
public class PromotionProjectionCheckpointRecord {
    private long auctionWindowId;
    private String lastDecisionId;
    private long lastDecisionVersion;
    private String lastStreamId;
}
