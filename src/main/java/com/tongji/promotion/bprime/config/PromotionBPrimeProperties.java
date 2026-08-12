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
    private int closingScanBatchSize = 100;
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
                || fastRejectMarginSeconds < 0 || fastRejectPriceCacheMaximumSize <= 0
                || closingScanBatchSize <= 0) {
            throw new IllegalStateException("invalid promotion.bprime configuration");
        }
        auctionRules.values().forEach(AuctionRules::validate);
    }

    /** 按资源位取英式拍卖规则；未配置时返回默认值（Go model.go Rules.Validate 同构：increment>0、cap=0 或 >reserve、反狙击 10s/10s/5 次）。 */
    public AuctionRules auctionRules(PromotionResourceType type) {
        return auctionRules.getOrDefault(type.placement().replace('_', '-'), AuctionRules.DEFAULT);
    }

    /** 英式升价拍卖规则（Go freeze_rules 的 increment/cap/extend 参数，创建期注入窗口 state）。 */
    public record AuctionRules(
            long incrementCents,
            long capPriceCents,
            long extendWindowSec,
            long extendSec,
            int maxExtensions
    ) {

        /** 默认规则：increment 100、cap 0（禁用）、反狙击 10s 窗口 / 10s 延长 / 5 次上限。 */
        public static final AuctionRules DEFAULT =
                new AuctionRules(100L, 0L, 10L, 10L, 5);

        void validate() {
            if (incrementCents <= 0) {
                throw new IllegalStateException("promotion.bprime.auction-rules incrementCents must be > 0");
            }
            if (capPriceCents < 0 || extendWindowSec < 0 || extendSec < 0 || maxExtensions < 0) {
                throw new IllegalStateException("promotion.bprime.auction-rules values must not be negative");
            }
        }

        /**
         * boundAntiSnipe（Go mode.go L39-44 同构）：反狙击开启但 maxExtensions=0 时注入 10 次上限，
         * 防止配置遗漏导致无限延长。
         */
        public AuctionRules withBoundAntiSnipe() {
            if (extendWindowSec > 0 && extendSec > 0 && maxExtensions == 0) {
                return new AuctionRules(incrementCents, capPriceCents, extendWindowSec, extendSec, 10);
            }
            return this;
        }
    }
}
