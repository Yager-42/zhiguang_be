package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionSnapshotServiceTest {

    @Test
    void snapshotPrefersRedisHotRankingWhenAvailable() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionSlotAllocationMapper allocationMapper = mock(PromotionSlotAllocationMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, String, String> hash = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(windowMapper.findById(301L)).thenReturn(PromotionAuctionWindow.builder()
                .id(301L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build());
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hash);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(zSet.reverseRange("promotion:auction:301:ranking", 0, 29)).thenReturn(Set.of("201"));
        when(hash.get("promotion:auction:301:campaign:201", "bidAmount")).thenReturn("120");
        when(hash.get("promotion:auction:301:campaign:201", "bidderUserId")).thenReturn("42");
        when(hash.get("promotion:auction:301:campaign:201", "postId")).thenReturn("1001");
        when(values.get("promotion:auction:301:decision_version")).thenReturn("7");
        PromotionSnapshotService service = new PromotionSnapshotService(windowMapper, allocationMapper, redisTemplate);

        PromotionAuctionSnapshot snapshot = service.snapshot(301L);

        assertThat(snapshot.status()).isEqualTo("OPEN");
        assertThat(snapshot.decisionVersion()).isEqualTo(7L);
        assertThat(snapshot.ranking()).hasSize(1);
        assertThat(snapshot.ranking().get(0).campaignId()).isEqualTo("201");
        assertThat(snapshot.ranking().get(0).bidAmount()).isEqualTo(120L);
        assertThat(snapshot.serverTime()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void closedSnapshotReadsMysqlAllocation() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionSlotAllocationMapper allocationMapper = mock(PromotionSlotAllocationMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(windowMapper.findById(301L)).thenReturn(PromotionAuctionWindow.builder()
                .id(301L)
                .status(PromotionAuctionWindowStatus.SETTLED)
                .build());
        when(allocationMapper.listByAuctionWindowId(301L)).thenReturn(List.of(PromotionSlotAllocation.builder()
                .auctionWindowId(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .slotIndex(0)
                .campaignId(201L)
                .postId(1001L)
                .bidderUserId(42L)
                .clearingPrice(100L)
                .build()));
        PromotionSnapshotService service = new PromotionSnapshotService(windowMapper, allocationMapper, redisTemplate);

        PromotionAuctionSnapshot snapshot = service.snapshot(301L);

        assertThat(snapshot.status()).isEqualTo("SETTLED");
        assertThat(snapshot.ranking()).hasSize(1);
        assertThat(snapshot.ranking().get(0).bidAmount()).isEqualTo(100L);
    }
}
