package com.tongji.promotion.bprime.model;

import lombok.Data;

/**
 * Persistent projection watermark for one auction window.
 *
 * @since 2026-08-08
 */
@Data
public class PromotionProjectionCheckpointRecord {
    private long auctionWindowId;
    private String lastDecisionId;
    private long lastDecisionVersion;
    private String lastKafkaTopic;
    private Integer lastKafkaPartition;
    private Long lastKafkaOffset;
}
