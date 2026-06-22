package com.tongji.promotion.bprime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionSnapshotServiceTest {

    @Test
    void snapshotPrefersRedisHotRankingWhenAvailable() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, String, String> hash = mock(HashOperations.class);
        when(windowMapper.findById(301L)).thenReturn(PromotionAuctionWindow.builder()
                .id(301L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build());
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hash);
        when(zSet.reverseRange("promotion:auction:301:ranking", 0, 29)).thenReturn(Set.of("201"));
        when(hash.get("promotion:auction:301:campaign:201", "bidAmount")).thenReturn("120");
        when(hash.get("promotion:auction:301:campaign:201", "bidderUserId")).thenReturn("42");
        when(hash.get("promotion:auction:301:campaign:201", "postId")).thenReturn("1001");
        PromotionSnapshotService service = new PromotionSnapshotService(windowMapper, decisionMapper,
                redisTemplate, new ObjectMapper().findAndRegisterModules());

        PromotionAuctionSnapshot snapshot = service.snapshot(301L);

        assertThat(snapshot.status()).isEqualTo("OPEN");
        assertThat(snapshot.ranking()).hasSize(1);
        assertThat(snapshot.ranking().get(0).campaignId()).isEqualTo(201L);
        assertThat(snapshot.ranking().get(0).bidAmount()).isEqualTo(120L);
        assertThat(snapshot.serverTime()).isBeforeOrEqualTo(Instant.now());
    }
}
