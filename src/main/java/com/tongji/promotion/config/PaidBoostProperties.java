package com.tongji.promotion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 付费加权（非拍卖）配置，绑定前缀 {@code promotion.paid-boost.*}。
 * <ul>
 *   <li>{@code active-cache-ttl-seconds}：active boost Redis 缓存 TTL。</li>
 *   <li>{@code delivery-bucket-seconds}：delivery 聚合时间桶粒度（秒）。</li>
 *   <li>{@code settle-delay-ms}：定时结算/关闭/缓存刷新调度间隔（毫秒）。</li>
 *   <li>{@code recommendation-max-boost-effect}：推荐排序 boost 效果上限。</li>
 *   <li>{@code follow-delivery-selection-cap}：关注流受限选择阶段的原始 timeline 拉取上限。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "promotion.paid-boost")
public class PaidBoostProperties {

    private long activeCacheTtlSeconds = 60L;
    private long deliveryBucketSeconds = 60L;
    private long settleDelayMs = 30000L;
    private long recommendationMaxBoostEffect = 50L;
    private int followDeliverySelectionCap = 40;
}
