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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        Objects.requireNonNull(decision, "decision must not be null");
        appendBatch(List.of(decision));
    }

    @Override
    public void appendBatch(List<PromotionAuctionDecision> decisions) {
        Objects.requireNonNull(decisions, "decisions must not be null");
        if (decisions.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] appends = decisions.stream()
                .map(this::appendAsync)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(appends)
                    .get(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while appending promotion decision batch", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Failed to append promotion decision batch to Kafka", exception);
        }
    }

    @Override
    public CompletionStage<Void> appendAsync(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        try {
            PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                    decision, PromotionDecisionHasher.hash(decision), Instant.now());
            String payload = objectMapper.writeValueAsString(envelope);
            return kafkaTemplate.send(
                            properties.getDecisionTopic(), String.valueOf(decision.auctionWindowId()), payload)
                    .orTimeout(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS)
                    .thenApply(ignored -> null);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }
}
