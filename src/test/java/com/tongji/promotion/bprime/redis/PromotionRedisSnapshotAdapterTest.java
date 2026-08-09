package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionHotSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionRedisSnapshotAdapterTest {

    @Test
    void parsesRankingAndVersionFromSingleRedisExecution() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(
                "promotion:auction:{301}:ranking", "promotion:auction:{301}:state")),
                eq("promotion:auction:{301}:campaign:"), eq("30")))
                .thenReturn("""
                        {"decisionVersion":7,"ranking":[{"campaignId":"201","bidderUserId":"42",
                        "postId":"1001","bidAmount":120,"rank":1}]}
                        """);
        PromotionRedisSnapshotAdapter adapter = new PromotionRedisSnapshotAdapter(
                redisTemplate, new ObjectMapper().findAndRegisterModules());

        PromotionAuctionHotSnapshot snapshot = adapter.snapshot(301L);

        assertThat(snapshot.decisionVersion()).isEqualTo(7L);
        assertThat(snapshot.ranking()).hasSize(1);
        assertThat(snapshot.ranking().get(0).campaignId()).isEqualTo("201");
        assertThat(snapshot.ranking().get(0).bidAmount()).isEqualTo(120L);
    }
}
