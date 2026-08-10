package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通过 Lua 幂等初始化窗口热状态并同步低频保证金授权。
 */
@Repository
public class PromotionAuctionHotStateRepository {

    private static final String SUCCESS = "OK";

    private final StringRedisTemplate redisTemplate;
    private final PromotionBPrimeProperties properties;
    private final DefaultRedisScript<String> initializeScript;
    private final DefaultRedisScript<String> escrowScript;

    public PromotionAuctionHotStateRepository(StringRedisTemplate redisTemplate,
                                               PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.initializeScript = script("redis/lua/promotion-auction-initialize.lua");
        this.escrowScript = script("redis/lua/promotion-auction-escrow.lua");
    }

    public void initialize(PromotionBidRoute route, long initialDecisionVersion) {
        PromotionBPrimeProperties.AuctionRules rules = properties.auctionRules(
                PromotionResourceType.valueOf(route.resourceType()));
        // 旧 route JSON（迁移期残留，缺英式字段 → 0）按资源位配置兜底，防止 increment=0 全收。
        long incrementCents = route.incrementCents() > 0 ? route.incrementCents() : rules.incrementCents();
        long capPriceCents = route.capPriceCents() > 0 ? route.capPriceCents() : rules.capPriceCents();
        long extendWindowSec = route.extendWindowSec() > 0 ? route.extendWindowSec() : rules.extendWindowSec();
        long extendSec = route.extendSec() > 0 ? route.extendSec() : rules.extendSec();
        int maxExtensions = route.maxExtensions() > 0 ? route.maxExtensions() : rules.maxExtensions();
        initialize(route.auctionWindowId(), route.windowEndAt().toEpochMilli(), route.reservePrice(),
                route.slotCount(), route.resourceType(), initialDecisionVersion,
                incrementCents, capPriceCents, extendWindowSec, extendSec, maxExtensions);
    }

    public void initialize(PromotionAuctionWindow window, long initialDecisionVersion) {
        PromotionBPrimeProperties.AuctionRules rules = properties.auctionRules(window.getResourceType());
        initialize(window.getId(), window.getWindowEndAt().toEpochMilli(), window.getReservePrice(),
                window.getSlotCount(), window.getResourceType().name(), initialDecisionVersion,
                rules.incrementCents(), rules.capPriceCents(),
                rules.extendWindowSec(), rules.extendSec(), rules.maxExtensions());
    }

    private void initialize(long auctionWindowId, long windowEndAtEpochMs, long reservePrice,
                            int slotCount, String resourceType, long initialDecisionVersion,
                            long incrementCents, long capPriceCents, long extendWindowSec,
                            long extendSec, int maxExtensions) {
        String result = redisTemplate.execute(initializeScript,
                PromotionAuctionRedisKeys.initializationKeys(auctionWindowId),
                String.valueOf(windowEndAtEpochMs),
                String.valueOf(reservePrice),
                String.valueOf(slotCount),
                resourceType,
                String.valueOf(initialDecisionVersion),
                String.valueOf(properties.getHotStateTtlSeconds()),
                String.valueOf(incrementCents),
                String.valueOf(capPriceCents),
                String.valueOf(extendWindowSec),
                String.valueOf(extendSec),
                String.valueOf(maxExtensions));
        requireSuccess(result, "initialize");
        Long registered = redisTemplate.opsForSet().add(
                PromotionAuctionRedisKeys.activeStreams(), String.valueOf(auctionWindowId));
        if (registered == null) {
            throw new PromotionAuctionUnavailableException("failed to register active promotion Stream");
        }
        redisTemplate.opsForZSet().add(PromotionAuctionRedisKeys.closingIndex(),
                String.valueOf(auctionWindowId), windowEndAtEpochMs);
    }

    public void projectAuthorization(PromotionBidRoute route) {
        String result = redisTemplate.execute(escrowScript,
                List.of(PromotionAuctionRedisKeys.state(route.auctionWindowId()),
                        PromotionAuctionRedisKeys.escrow(route.auctionWindowId())),
                String.valueOf(route.campaignId()),
                String.valueOf(route.authorizedAmount()),
                String.valueOf(properties.getHotStateTtlSeconds()));
        requireSuccess(result, "project escrow authorization");
    }

    private DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }

    private void requireSuccess(String result, String action) {
        if (!SUCCESS.equals(result)) {
            throw new PromotionAuctionUnavailableException(
                    "failed to " + action + ": " + (result == null ? "empty Redis result" : result));
        }
    }
}
