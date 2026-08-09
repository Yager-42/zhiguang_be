package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/** Initializes the Redis auction state before ordered commands enter the hot path. */
@Repository
public class PromotionAuctionHotStateRepository {

    private final StringRedisTemplate redisTemplate;
    private final PromotionBPrimeProperties properties;

    public PromotionAuctionHotStateRepository(StringRedisTemplate redisTemplate,
                                               PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void initialize(PromotionBidRoute route, long initialDecisionVersion) {
        String stateKey = PromotionAuctionRedisKeys.prefix(route.auctionWindowId()) + ":state";
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        hashOperations.putIfAbsent(stateKey, "decisionVersion", String.valueOf(initialDecisionVersion));
        hashOperations.putIfAbsent(stateKey, "status", route.windowStatus());
        hashOperations.putIfAbsent(stateKey, "reservePrice", String.valueOf(route.reservePrice()));
        hashOperations.putIfAbsent(stateKey, "resourceType", route.resourceType());
        redisTemplate.expire(stateKey, Duration.ofSeconds(properties.getHotStateTtlSeconds()));
    }
}
