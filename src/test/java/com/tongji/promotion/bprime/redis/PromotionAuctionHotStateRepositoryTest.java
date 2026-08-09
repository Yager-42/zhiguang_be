package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAuctionHotStateRepositoryTest {

    @Test
    void initializesClusterSafeStateFromProjectionCheckpoint() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setHotStateTtlSeconds(600L);
        PromotionAuctionHotStateRepository repository =
                new PromotionAuctionHotStateRepository(redisTemplate, properties);

        repository.initialize(new PromotionBidRoute(
                201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L,
                "OPEN", Instant.parse("2026-08-08T12:00:00Z")), 17L);

        String stateKey = "promotion:auction:{301}:state";
        verify(hashOperations).putIfAbsent(stateKey, "decisionVersion", "17");
        verify(hashOperations).putIfAbsent(stateKey, "status", "OPEN");
        verify(hashOperations).putIfAbsent(stateKey, "reservePrice", "100");
        verify(hashOperations).putIfAbsent(stateKey, "resourceType", "FEED_TOP_SLOT");
        verify(redisTemplate).expire(stateKey, Duration.ofSeconds(600L));
    }
}
