package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchResult;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandBatch;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionRedisDecisionAdapterTest {

    @Test
    void sendsClusterSafeDynamicKeysAndDecodesCompactBatch() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PromotionRedisDecisionAdapter adapter = new PromotionRedisDecisionAdapter(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new PromotionBPrimeProperties());
        PromotionAuctionCommand command = new PromotionAuctionCommand("cmd-1", "idem", "hash-1", 301L, 201L,
                42L, 1001L, "FEED_TOP_SLOT", 120L, 100L, "OPEN", "BID",
                Instant.parse("2026-06-20T10:05:00Z"));
        List<String> keys = List.of(
                "promotion:auction:{301}:state",
                "promotion:auction:{301}:ranking",
                "promotion:auction:{301}:escrow",
                "promotion:auction:{301}:events",
                "promotion:auction:{301}:pub",
                "promotion:auction:{301}:wakeup",
                "promotion:auction:{301}:campaign:201");
        List<Object> raw = List.of("OK", List.of(List.of(
                "0", "ACCEPTED", "cmd-1:v2", "2", "1", "1781949900000",
                "2026-06-20T10:05:00Z", "500", "120", "", "220", "120")),
                "120", "cmd-1", "201", "OPEN", "1781953200000", "2");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(raw);

        PromotionAuctionBatchResult result = adapter.decide(new PromotionAuctionCommandBatch(301L, List.of(command)));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.decision().accepted()).isTrue();
            assertThat(item.decision().decisionVersion()).isEqualTo(2L);
        });
        verify(redisTemplate).execute(any(RedisScript.class), eq(keys), any(Object[].class));
    }
}
