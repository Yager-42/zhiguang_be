package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
        // 旧 route JSON 缺价格规则时按资源位配置兜底，防止 increment=0 全收。
        long incrementCents = route.incrementCents() > 0 ? route.incrementCents() : rules.incrementCents();
        long capPriceCents = route.capPriceCents() > 0 ? route.capPriceCents() : rules.capPriceCents();
        initialize(route.auctionWindowId(), route.windowEndAt().toEpochMilli(), route.reservePrice(),
                route.slotCount(), route.resourceType(), initialDecisionVersion, incrementCents, capPriceCents);
    }

    public void initialize(PromotionAuctionWindow window, long initialDecisionVersion) {
        PromotionBPrimeProperties.AuctionRules rules = properties.auctionRules(window.getResourceType());
        initialize(window.getId(), window.getWindowEndAt().toEpochMilli(), window.getReservePrice(),
                window.getSlotCount(), window.getResourceType().name(), initialDecisionVersion,
                rules.incrementCents(), rules.capPriceCents());
    }

    private void initialize(long auctionWindowId, long windowEndAtEpochMs, long reservePrice,
                            int slotCount, String resourceType, long initialDecisionVersion,
                            long incrementCents, long capPriceCents) {
        String result = redisTemplate.execute(initializeScript,
                PromotionAuctionRedisKeys.initializationKeys(auctionWindowId),
                String.valueOf(windowEndAtEpochMs),
                String.valueOf(reservePrice),
                String.valueOf(slotCount),
                resourceType,
                String.valueOf(initialDecisionVersion),
                String.valueOf(properties.getHotStateTtlSeconds()),
                String.valueOf(incrementCents),
                String.valueOf(capPriceCents));
        requireSuccess(result, "initialize");
        Long registered = redisTemplate.opsForSet().add(
                PromotionAuctionRedisKeys.activeStreams(), String.valueOf(auctionWindowId));
        if (registered == null) {
            throw new PromotionAuctionUnavailableException("failed to register active promotion Stream");
        }
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
    /**
     * 验证全部 MySQL OPEN 窗口的既有热状态后，才恢复 Stream 活跃集合。
     *
     * <p>每个 HGET 是 Redis 的原子只读操作。围栏先完成所有验证，故任一遗留 deadline
     * 或缺失状态均不会写入 registry，更不会以新 deadline 重置旧状态。</p>
     *
     * @param activeWindows MySQL 权威 OPEN 窗口
     * @throws PromotionAuctionUnavailableException 当状态缺失、deadline 不匹配或 Redis 检查失败时
     */
    public void verifyAndRecoverActiveWindows(List<PromotionAuctionWindow> activeWindows) {
        if (activeWindows == null || activeWindows.isEmpty()) {
            return;
        }
        try {
            for (PromotionAuctionWindow window : activeWindows) {
                Objects.requireNonNull(window, "active window must not be null");
                Object windowEndAtEpochMs = redisTemplate.opsForHash().get(
                        PromotionAuctionRedisKeys.state(window.getId()), "windowEndAtEpochMs");
                String expectedWindowEndAtEpochMs = String.valueOf(window.getWindowEndAt().toEpochMilli());
                if (windowEndAtEpochMs == null) {
                    throw new PromotionAuctionUnavailableException(
                            "missing promotion auction hot state for OPEN window " + window.getId());
                }
                if (!expectedWindowEndAtEpochMs.equals(String.valueOf(windowEndAtEpochMs))) {
                    throw new PromotionAuctionUnavailableException(
                            "promotion auction hot-state deadline mismatch for OPEN window " + window.getId());
                }
            }
            String[] windowIds = activeWindows.stream()
                    .map(window -> String.valueOf(window.getId()))
                    .toArray(String[]::new);
            Long registered = redisTemplate.opsForSet().add(PromotionAuctionRedisKeys.activeStreams(), windowIds);
            if (registered == null) {
                throw new PromotionAuctionUnavailableException("failed to recover active promotion Streams");
            }
        } catch (PromotionAuctionUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("failed to verify promotion auction hot state", exception);
        }
    }

    public void retireSettled(long auctionWindowId) {
        Duration ttl = Duration.ofSeconds(properties.getHotStateTtlSeconds());
        redisTemplate.opsForHash().put(PromotionAuctionRedisKeys.state(auctionWindowId), "status", "SETTLED");
        redisTemplate.expire(PromotionAuctionRedisKeys.state(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.ranking(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.escrow(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.events(auctionWindowId), ttl);
        redisTemplate.opsForSet().remove(PromotionAuctionRedisKeys.activeStreams(), String.valueOf(auctionWindowId));
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
