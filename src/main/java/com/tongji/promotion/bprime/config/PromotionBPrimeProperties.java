package com.tongji.promotion.bprime.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elia 风格推广竞价热链路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "promotion.bprime")
public class PromotionBPrimeProperties {

    private boolean enabled;
    private long hotStateTtlSeconds = 86_400L;
    private long commandIdempotencyTtlSeconds = 300L;
    /** 幂等字段 TTL 余量（HSETEX 字段 TTL = commandIdempotencyTtlSeconds + 本值，保持原桶 300-360s 有效窗口）。 */
    private long commandIdempotencyBucketSeconds = 60L;
    private long streamSweepIntervalMs = 2_000L;
    private int streamReadBatchSize = 1_000;
    private long streamRetainEvents = 100_000L;
    private long settledCompensationLookbackSeconds = 604_800L;
    private int bidSubmissionThreadCount = 32;
    private int bidSubmissionQueueCapacity = 16_384;
    private long bidRouteCacheMaximumSize = 100_000L;
    private int webSocketInboundThreadCount = 8;
    private int webSocketOutboundThreadCount = 32;
    private int webSocketChannelQueueCapacity = 65_536;
    private int webSocketNativeSessionQueueCapacity = 4_096;
    private long publicUpdateFlushIntervalMs = 100L;
    private long publicUpdateMaximumFlushIntervalMs = 250L;
    private int publicUpdateAdaptiveSubscriberCeiling = 500;
    private int publicUpdateSchedulerThreadCount = 2;
    private long publicUpdateMaximumWindows = 10_000L;
    private boolean fastRejectEnabled = true;
    private long fastRejectMarginSeconds = 2L;
    private long fastRejectPriceCacheMaximumSize = 1_000_000L;
    private int closingScanBatchSize = 100;

    @PostConstruct
    void validate() {
        if (hotStateTtlSeconds <= 0 || commandIdempotencyTtlSeconds <= 0
                || commandIdempotencyBucketSeconds < 0
                || streamSweepIntervalMs <= 0 || streamReadBatchSize <= 0 || streamReadBatchSize > 1_000
                || streamRetainEvents < 100_000L || settledCompensationLookbackSeconds <= 0
                || bidSubmissionThreadCount <= 0 || bidSubmissionQueueCapacity <= 0
                || bidRouteCacheMaximumSize <= 0
                || webSocketInboundThreadCount <= 0 || webSocketOutboundThreadCount <= 0
                || webSocketChannelQueueCapacity <= 0 || webSocketNativeSessionQueueCapacity <= 0
                || publicUpdateFlushIntervalMs <= 0
                || publicUpdateMaximumFlushIntervalMs < publicUpdateFlushIntervalMs
                || publicUpdateAdaptiveSubscriberCeiling <= 0 || publicUpdateSchedulerThreadCount <= 0
                || publicUpdateMaximumWindows <= 0
                || fastRejectMarginSeconds < 0 || fastRejectPriceCacheMaximumSize <= 0
                || closingScanBatchSize <= 0) {
        }
    }
}
