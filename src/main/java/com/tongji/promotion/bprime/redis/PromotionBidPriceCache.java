package com.tongji.promotion.bprime.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每窗口共享当前价缓存（只升不降），供网关侧 fast-reject 使用（Go ws.go 窗口级缓存同构）。
 *
 * <p>由投影器在读取权威 Stream 时更新（单消费者、按版本序，天然单调；BID_ACCEPTED 的
 * bidAmount 即新当前价）；缓存值只升不降，投影 lag 只会让缓存落后于 Lua 实际价——预拒方向安全。
 * 终态决策（AUCTION_SOLD/AUCTION_NO_BID）到达时 {@link #invalidate(long)} 清空，
 * 下一出价放行 Lua 返回 WINDOW_CLOSED（避免 route 仍 OPEN 时误拒成 BID_NOT_HIGHER）。</p>
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
    public void update(long auctionWindowId, long currentPriceCents) {
        String key = String.valueOf(auctionWindowId);
        prices.asMap().compute(key, (ignored, current) ->
                current == null || currentPriceCents > current ? currentPriceCents : current);
    }

    /** 返回缓存当前价；无缓存返回 {@code null}。 */
    public Long get(long auctionWindowId) {
        return prices.getIfPresent(String.valueOf(auctionWindowId));
    }

    /** 终态决策到达时清空该窗口缓存（Go updateRoomStateFromEvent 同构）。 */
    public void invalidate(long auctionWindowId) {
        prices.invalidate(String.valueOf(auctionWindowId));
    }
}
