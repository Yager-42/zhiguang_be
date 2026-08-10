package com.tongji.promotion.bprime.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每 campaign 当前最高出价缓存（只升不降），供网关侧 fast-reject 使用。
 *
 * <p>由投影器在读取权威 Stream 时更新（单消费者、按版本序，天然单调）；缓存值只升不降，
 * 投影 lag 只会让缓存落后于 Lua 实际价——预拒方向安全（更保守）。窗口关闭后残留无害：
 * 预拒条件另有 {@code windowStatus==OPEN} 守卫。</p>
 */
@Component
public class PromotionBidPriceCache {

    private final Cache<String, Long> prices;

    public PromotionBidPriceCache(PromotionBPrimeProperties properties) {
        this.prices = Caffeine.newBuilder()
                .maximumSize(properties.getFastRejectPriceCacheMaximumSize())
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
    }

    /** 单调上升更新；现价 >= 新价时忽略。 */
    public void update(long auctionWindowId, long campaignId, long bidAmount) {
        String key = key(auctionWindowId, campaignId);
        prices.asMap().compute(key, (ignored, current) ->
                current == null || bidAmount > current ? bidAmount : current);
    }

    /** 返回缓存价；无缓存返回 {@code null}。 */
    public Long get(long auctionWindowId, long campaignId) {
        return prices.getIfPresent(key(auctionWindowId, campaignId));
    }

    private String key(long auctionWindowId, long campaignId) {
        return auctionWindowId + ":" + campaignId;
    }
}
