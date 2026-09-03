package com.tongji.promotion.bprime.config;

import com.tongji.promotion.model.PromotionResourceType;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Elia 风格推广竞价热链路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "promotion.bprime")
public class PromotionBPrimeProperties {

    private boolean enabled;
    private long hotStateTtlSeconds = 86_400L;
    private int bidDecisionBatchMaximumSize = 256;
    private int bidDecisionBatchMaximumArgumentBytes = 262_144;
    private long streamSweepIntervalMs = 2_000L;
    private int streamReadBatchSize = 1_000;
    private long streamRetainEvents = 100_000L;
    private long settledCompensationLookbackSeconds = 604_800L;
    private int bidDrainerThreadCount = 8;
    private int bidReadyWindowQueueCapacity = 16_384;
    private int bidWindowPendingCapacity = 8_192;
    private int bidGlobalPendingCapacity = 65_536;
    private long bidCombinerMaximumWindows = 100_000L;
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
    /** 英式升价拍卖规则，按资源位配置（key = 资源位 kebab-case，如 feed-top-slot）。 */
    private Map<String, AuctionRules> auctionRules = new HashMap<>();

    @PostConstruct
    void validate() {
        if (hotStateTtlSeconds <= 0
                || bidDecisionBatchMaximumSize <= 0 || bidDecisionBatchMaximumSize > 1_000
                || bidDecisionBatchMaximumArgumentBytes <= 0
                || streamSweepIntervalMs <= 0 || streamReadBatchSize <= 0 || streamReadBatchSize > 1_000
                || streamRetainEvents < 100_000L || settledCompensationLookbackSeconds <= 0
                || bidDrainerThreadCount <= 0 || bidReadyWindowQueueCapacity <= 0
                || bidWindowPendingCapacity <= 0 || bidGlobalPendingCapacity < bidWindowPendingCapacity
                || bidCombinerMaximumWindows <= 0 || bidRouteCacheMaximumSize <= 0
                || webSocketInboundThreadCount <= 0 || webSocketOutboundThreadCount <= 0
                || webSocketChannelQueueCapacity <= 0 || webSocketNativeSessionQueueCapacity <= 0
                || publicUpdateFlushIntervalMs <= 0
                || publicUpdateMaximumFlushIntervalMs < publicUpdateFlushIntervalMs
                || publicUpdateAdaptiveSubscriberCeiling <= 0 || publicUpdateSchedulerThreadCount <= 0
                || publicUpdateMaximumWindows <= 0
                || fastRejectMarginSeconds < 0 || fastRejectPriceCacheMaximumSize <= 0) {
            throw new IllegalStateException("invalid promotion.bprime configuration");
        }
        auctionRules.values().forEach(AuctionRules::validate);
    }

    /** 按资源位取英式拍卖规则；未配置时返回默认价格台阶与一口价规则。 */
    public AuctionRules auctionRules(PromotionResourceType type) {
        return auctionRules.getOrDefault(type.placement().replace('_', '-'), AuctionRules.DEFAULT);
    }

    /** 创建窗口热状态时冻结的英式升价规则。 */
    public record AuctionRules(long incrementCents, long capPriceCents) {

        /** 默认规则：increment 100、cap 0（禁用一口价）。 */
        public static final AuctionRules DEFAULT = new AuctionRules(100L, 0L);

        void validate() {
            if (incrementCents <= 0) {
                throw new IllegalStateException("promotion.bprime.auction-rules incrementCents must be > 0");
            }
            if (capPriceCents < 0) {
                throw new IllegalStateException("promotion.bprime.auction-rules capPriceCents must not be negative");
            }
        }
    }
}
