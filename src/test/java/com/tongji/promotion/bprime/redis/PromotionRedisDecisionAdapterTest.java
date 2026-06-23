package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionRedisDecisionAdapterTest {

    @Test
    void sendsExpectedKeysAndArgs() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionRedisDecisionAdapter adapter = new PromotionRedisDecisionAdapter(redisTemplate, objectMapper);
        PromotionAuctionCommand command = command("cmd-1", "hash-1", 42L, 120L);
        String json = """
                {"decisionId":"cmd-1:decision","commandId":"cmd-1","requestHash":"hash-1","auctionWindowId":301,
                "decisionVersion":2,"previousVersion":1,"campaignId":201,"bidderUserId":42,"postId":1001,
                "resourceType":"FEED_TOP_SLOT","type":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                """;
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(
                "promotion:auction:301:state",
                "promotion:auction:301:commands",
                "promotion:auction:301:ranking",
                "promotion:auction:301:campaign:201",
                "promotion:auction:301:decision_version"
        )), eq("cmd-1"), eq("hash-1"), eq("42"), eq("120"), eq("100"), eq("1781949900000"),
                eq("OPEN"), eq("301"), eq("201"), eq("1001"), eq("FEED_TOP_SLOT"))).thenReturn(json);

        PromotionAuctionDecision decision = adapter.decide(command, 100L, "OPEN",
                Instant.parse("2026-06-20T10:05:00Z"));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.decisionVersion()).isEqualTo(2L);
        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    void rollbackClearsReplayEntryAndRewindsVersionOnlyWhenCurrentDecisionStillOwnsCounter() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("promotion:auction:301:decision_version")).thenReturn("2");
        PromotionRedisDecisionAdapter adapter = new PromotionRedisDecisionAdapter(redisTemplate, objectMapper);
        PromotionAuctionDecision decision = new PromotionAuctionDecision("cmd-1:decision", "cmd-1", "hash-1",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 120L, List.of(), List.of(), java.util.Map.of(), Instant.parse("2026-06-20T10:05:00Z"));

        adapter.rollback(decision);

        verify(hashOps).delete("promotion:auction:301:commands", "cmd-1:hash", "cmd-1:decision");
        verify(valueOps).set("promotion:auction:301:decision_version", "1");
    }

    private PromotionAuctionCommand command(String commandId, String hash, long bidder, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, 301L, 201L, bidder, 1001L,
                "FEED_TOP_SLOT", amount, "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }
}
