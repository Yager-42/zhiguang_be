package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

@Repository
public class PromotionBidRouteRepository {

    private static final Duration MINIMUM_TTL = Duration.ofMinutes(5);
    private static final Duration POST_WINDOW_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PromotionBidRouteRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(PromotionBidRoute route, Instant now) {
        Duration ttl = Duration.between(now, route.windowEndAt().plus(POST_WINDOW_TTL));
        if (ttl.compareTo(MINIMUM_TTL) < 0) {
            ttl = MINIMUM_TTL;
        }
        try {
            redisTemplate.opsForValue().set(key(route.campaignId()), objectMapper.writeValueAsString(route), ttl);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to store promotion bid route", exception);
        }
    }

    public PromotionBidRoute find(long campaignId) {
        String payload = redisTemplate.opsForValue().get(key(campaignId));
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, PromotionBidRoute.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read promotion bid route", exception);
        }
    }

    private String key(long campaignId) {
        return "promotion:bprime:route:" + campaignId;
    }
}
