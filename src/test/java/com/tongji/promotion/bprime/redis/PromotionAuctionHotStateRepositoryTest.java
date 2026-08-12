package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAuctionHotStateRepositoryTest {

    @Test
    void initializesAllWindowKeysInOneClusterSlot() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("OK");
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(setOps.add(anyString(), any(String[].class))).thenReturn(1L);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        PromotionAuctionHotStateRepository repository =
                new PromotionAuctionHotStateRepository(redisTemplate, new PromotionBPrimeProperties());

        repository.initialize(new PromotionBidRoute(201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L, "OPEN", Instant.parse("2026-08-08T12:00:00Z"), 2), 17L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                "promotion:auction:{301}:state",
                "promotion:auction:{301}:ranking",
                "promotion:auction:{301}:escrow",
                "promotion:auction:{301}:events");
    }
}
