package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void sendsExpectedKeysAndArgs() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionRedisDecisionAdapter adapter = new PromotionRedisDecisionAdapter(redisTemplate, objectMapper);
        PromotionAuctionCommand command = command("cmd-1", "hash-1", 42L, 120L);
        String json = """
                {"decisionId":"cmd-1:decision","commandId":"cmd-1","requestHash":"hash-1","auctionWindowId":301,
                "campaignId":201,"bidderUserId":42,"postId":1001,"resourceType":"FEED_TOP_SLOT",
                "decisionType":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                """;
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(
                "promotion:auction:301:state",
                "promotion:auction:301:commands",
                "promotion:auction:301:ranking",
                "promotion:auction:301:campaign:201"
        )), eq("cmd-1"), eq("hash-1"), eq("42"), eq("120"), eq("100"), eq("1781949900000"),
                eq("OPEN"), eq("301"), eq("201"), eq("1001"), eq("FEED_TOP_SLOT"))).thenReturn(json);

        PromotionAuctionDecision decision = adapter.decide(command, 100L, "OPEN",
                Instant.parse("2026-06-20T10:05:00Z"));

        assertThat(decision.accepted()).isTrue();
        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    private PromotionAuctionCommand command(String commandId, String hash, long bidder, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, 301L, 201L, bidder, 1001L,
                "FEED_TOP_SLOT", amount, "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }
}
