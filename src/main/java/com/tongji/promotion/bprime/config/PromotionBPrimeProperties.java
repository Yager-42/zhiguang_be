package com.tongji.promotion.bprime.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "promotion.bprime")
public class PromotionBPrimeProperties {

    private boolean enabled;
    private String commandTopic = "zhiguang_promotion_auction_commands_v2";
    private String commandConsumerGroup = "zhiguang-promotion-command-consumer";
    private String decisionTopic = "zhiguang.promotion.auction.decisions.v2";
    private String projectionConsumerGroup = "zhiguang-promotion-projection-consumer";
    private long kafkaSendTimeoutMs = 10000L;
    private long hotStateTtlSeconds = 86400L;
    private int feedSlotCount = 1;
    private int searchSlotCount = 1;
    private long feedReservePrice = 1L;
    private long searchReservePrice = 1L;

    @PostConstruct
    void validate() {
        requireText(commandTopic, "promotion.bprime.command-topic");
        requireText(commandConsumerGroup, "promotion.bprime.command-consumer-group");
        requireText(decisionTopic, "promotion.bprime.decision-topic");
        requireText(projectionConsumerGroup, "promotion.bprime.projection-consumer-group");
        if (kafkaSendTimeoutMs <= 0 || hotStateTtlSeconds <= 0
                || feedSlotCount <= 0 || searchSlotCount <= 0
                || feedReservePrice <= 0 || searchReservePrice <= 0) {
            throw new IllegalStateException("promotion.bprime numeric config must be positive");
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
    }
}
