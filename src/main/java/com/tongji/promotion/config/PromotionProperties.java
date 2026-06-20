package com.tongji.promotion.config;

import com.tongji.promotion.model.PromotionResourceType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推广位竞价配置，绑定前缀 {@code promotion.slot-auction.*}。
 * <ul>
 *   <li>{@code *-slot-count}：各资源位窗口槽位数。</li>
 *   <li>{@code *-reserve-price}：各资源位保留价（成交价下限）。</li>
 *   <li>{@code window-minutes}：单个竞价窗口时长（分钟）。</li>
 *   <li>{@code cache-ttl-seconds}：active allocation 缓存 TTL。</li>
 *   <li>{@code settle-batch-size}：单轮结算扫描窗口数。</li>
 *   <li>{@code close-window-delay-ms}：定时关闭/续建调度间隔（毫秒）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "promotion.slot-auction")
public class PromotionProperties {

    private int feedTopSlotCount = 1;
    private int searchTopSlotCount = 1;
    private long feedReservePrice = 1L;
    private long searchReservePrice = 1L;
    private int windowMinutes = 60;
    private long cacheTtlSeconds = 300L;
    private int settleBatchSize = 50;
    private long closeWindowDelayMs = 30000L;

    /** 按资源类型取窗口槽位数。 */
    public int slotCount(PromotionResourceType type) {
        return type == PromotionResourceType.FEED_TOP_SLOT ? feedTopSlotCount : searchTopSlotCount;
    }

    /** 按资源类型取保留价。 */
    public long reservePrice(PromotionResourceType type) {
        return type == PromotionResourceType.FEED_TOP_SLOT ? feedReservePrice : searchReservePrice;
    }
}
