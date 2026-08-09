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
    private String fanoutConsumerGroup = "zhiguang-promotion-fanout-consumer";
    private boolean decisionConsumerEnabled = true;
    private boolean projectionConsumerEnabled = true;
    private boolean fanoutConsumerEnabled = true;
    private long kafkaSendTimeoutMs = 10000L;
    private long hotStateTtlSeconds = 86400L;
    private long commandIdempotencyTtlSeconds = 86400L;
    private long settledCompensationLookbackSeconds = 604800L;
    private boolean legacyCommandRecoveryEnabled;
    private long commandPublishScanDelayMs = 1000L;
    private long commandPublishClaimTimeoutMs = 5000L;
    private long commandDecisionTimeoutMs = 30000L;
    private int commandPublishBatchSize = 100;
    private int commandPublisherWorkerCount = 8;
    private int decisionMaxInFlight = 1024;
    private int decisionBatchSize = 128;
    private int decisionConsumerThreadCount = 4;
    private long decisionBatchSuspendMs = 1000L;
    private int projectionConcurrency = 1;
    private int fanoutConcurrency = 1;
    private boolean fastRejectEnabled = true;
    private long fastRejectMaximumCampaigns = 200_000L;
    private long fastRejectExpireAfterAccessSeconds = 7200L;
    private long fastRejectExpiryMarginMs = 1000L;
    private int fastRejectPrecheckBatchSize = 512;
    private int fastRejectPrecheckWorkerCount = 2;
    private int fastRejectPrecheckQueueCapacity = 65_536;
    private long fastRejectPrecheckMaxWaitMicros = 200L;
    private int webSocketInboundThreadCount = 8;
    private int webSocketOutboundThreadCount = 8;
    private int webSocketChannelQueueCapacity = 65_536;
    private int webSocketNativeSessionQueueCapacity = 4_096;
    private long publicUpdateFlushIntervalMs = 25L;
    private int publicUpdateSchedulerThreadCount = 2;
    private long publicUpdateMaximumWindows = 10_000L;
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
        requireText(fanoutConsumerGroup, "promotion.bprime.fanout-consumer-group");
        if (kafkaSendTimeoutMs <= 0 || hotStateTtlSeconds <= 0 || commandIdempotencyTtlSeconds <= 0
                || settledCompensationLookbackSeconds <= 0
                || commandPublishScanDelayMs <= 0 || commandPublishClaimTimeoutMs <= 0
                || commandDecisionTimeoutMs <= 0 || commandPublishBatchSize <= 0
                || commandPublisherWorkerCount <= 0 || decisionMaxInFlight <= 0 || decisionBatchSize <= 0
                || decisionBatchSize > decisionMaxInFlight || decisionConsumerThreadCount <= 0
                || decisionBatchSuspendMs <= 0 || projectionConcurrency <= 0 || fanoutConcurrency <= 0
                || fastRejectMaximumCampaigns <= 0 || fastRejectExpireAfterAccessSeconds <= 0
                || fastRejectExpiryMarginMs < 0
                || fastRejectPrecheckBatchSize <= 0 || fastRejectPrecheckWorkerCount <= 0
                || fastRejectPrecheckQueueCapacity <= 0 || fastRejectPrecheckMaxWaitMicros < 0
                || webSocketInboundThreadCount <= 0 || webSocketOutboundThreadCount <= 0
                || webSocketChannelQueueCapacity <= 0 || webSocketNativeSessionQueueCapacity <= 0
                || publicUpdateFlushIntervalMs <= 0 || publicUpdateSchedulerThreadCount <= 0
                || publicUpdateMaximumWindows <= 0
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
