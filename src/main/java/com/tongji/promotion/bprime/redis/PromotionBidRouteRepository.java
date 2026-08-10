package com.tongji.promotion.bprime.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

@Repository
public class PromotionBidRouteRepository {

    private static final Duration MINIMUM_TTL = Duration.ofMinutes(5);
    private static final Duration POST_WINDOW_TTL = Duration.ofMinutes(10);
    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<Long, PromotionBidRoute> routeCache;

    public PromotionBidRouteRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                       PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.routeCache = Caffeine.newBuilder()
                .maximumSize(properties.getBidRouteCacheMaximumSize())
                .expireAfterAccess(LOCAL_CACHE_TTL)
                .build();
    }

    public void save(PromotionBidRoute route, Instant now) {
        Duration ttl = Duration.between(now, route.windowEndAt().plus(POST_WINDOW_TTL));
        if (ttl.compareTo(MINIMUM_TTL) < 0) {
            ttl = MINIMUM_TTL;
        }
        try {
            redisTemplate.opsForValue().set(key(route.campaignId()), objectMapper.writeValueAsString(route), ttl);
            routeCache.put(route.campaignId(), route);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to store promotion bid route", exception);
        }
    }

    public PromotionBidRoute find(long campaignId) {
        PromotionBidRoute cached = routeCache.getIfPresent(campaignId);
        if (cached != null) {
            return cached;
        }
        String payload = redisTemplate.opsForValue().get(key(campaignId));
        if (payload == null) {
            return null;
        }
        try {
            PromotionBidRoute route = objectMapper.readValue(payload, PromotionBidRoute.class);
            routeCache.put(campaignId, route);
            return route;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read promotion bid route", exception);
        }
    }

    private String key(long campaignId) {
        return "promotion:bprime:route:" + campaignId;
    }
}
