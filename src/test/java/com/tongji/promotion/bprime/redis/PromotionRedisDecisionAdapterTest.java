package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
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
    void sendsClusterSafeKeysAndCommandTypeToSingleLuaDecision() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PromotionRedisDecisionAdapter adapter = new PromotionRedisDecisionAdapter(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new PromotionBPrimeProperties());
        PromotionAuctionCommand command = new PromotionAuctionCommand("cmd-1", "idem", "hash-1", 301L, 201L,
                42L, 1001L, "FEED_TOP_SLOT", 120L, 100L, "OPEN", "BID",
                Instant.parse("2026-06-20T10:05:00Z"));
        String json = """
                {"decisionId":"cmd-1:v2","commandId":"cmd-1","requestHash":"hash-1",
                "auctionWindowId":"301","decisionVersion":2,"previousVersion":1,"campaignId":"201",
                "bidderUserId":"42","postId":"1001","resourceType":"FEED_TOP_SLOT","type":"BID_ACCEPTED",
                "accepted":true,"rejectionReason":null,"bidAmount":120,"ranking":[],"walletEffects":[],
                "payload":{"authorizedAmount":500},"decidedAtEpochMs":1781949900000}
                """;
        List<String> keys = List.of(
                "promotion:auction:{301}:state",
                "promotion:auction:{301}:commands",
                "promotion:auction:{301}:ranking",
                "promotion:auction:{301}:campaign:201",
                "promotion:auction:{301}:escrow",
                "promotion:auction:{301}:events",
                "promotion:auction:{301}:pub",
                "promotion:auction:{301}:wakeup");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(json);

        PromotionAuctionDecision decision = adapter.decide(command, Instant.parse("2026-06-20T10:05:00Z"));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.decisionVersion()).isEqualTo(2L);
        verify(redisTemplate).execute(any(RedisScript.class), eq(keys), any(Object[].class));
    }
}
