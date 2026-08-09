package com.tongji.promotion.bprime.model;

/**
 * Carries one validated Kafka decision and its source position into the transactional projection batch.
 *
 * @since 2026-08-08
 */
public record PromotionDecisionProjectionItem(
        PromotionAuctionDecision decision,
        String kafkaTopic,
        Integer kafkaPartition,
        Long kafkaOffset
) {}
