package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class KafkaPromotionDecisionLogPort implements PromotionDecisionLogPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionBPrimeProperties properties;

    public KafkaPromotionDecisionLogPort(
            @Qualifier("promotionDecisionKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            PromotionBPrimeProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void append(PromotionAuctionDecision decision) {
        try {
            appendAsync(decision).toCompletableFuture()
                    .get(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append promotion decision to Kafka", e);
        }
    }

    @Override
    public CompletionStage<Void> appendAsync(PromotionAuctionDecision decision) {
        try {
            PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                    decision, PromotionDecisionHasher.hash(decision), Instant.now());
            String payload = objectMapper.writeValueAsString(envelope);
            return kafkaTemplate.send(properties.getDecisionTopic(), String.valueOf(decision.auctionWindowId()), payload)
                    .orTimeout(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS)
                    .thenApply(ignored -> null);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }
}
