package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class KafkaPromotionDecisionLogPort implements PromotionDecisionLogPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionBPrimeProperties properties;

    public KafkaPromotionDecisionLogPort(KafkaTemplate<String, String> kafkaTemplate,
                                         ObjectMapper objectMapper,
                                         PromotionBPrimeProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void append(PromotionAuctionDecision decision) {
        try {
            String payload = objectMapper.writeValueAsString(decision);
            kafkaTemplate.send(properties.getDecisionTopic(), String.valueOf(decision.auctionWindowId()), payload)
                    .get(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append promotion decision to Kafka", e);
        }
    }
}
