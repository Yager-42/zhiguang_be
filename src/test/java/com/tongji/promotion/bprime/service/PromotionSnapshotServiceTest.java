package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.model.PromotionAuctionHotSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.bprime.redis.PromotionRedisSnapshotAdapter;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionSnapshotServiceTest {

    @Test
    void snapshotPrefersRedisHotRankingWhenAvailable() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionSlotAllocationMapper allocationMapper = mock(PromotionSlotAllocationMapper.class);
        PromotionRedisSnapshotAdapter redisSnapshotAdapter = mock(PromotionRedisSnapshotAdapter.class);
        when(windowMapper.findById(301L)).thenReturn(PromotionAuctionWindow.builder()
                .id(301L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build());
        when(redisSnapshotAdapter.snapshot(301L)).thenReturn(new PromotionAuctionHotSnapshot(7L,
                List.of(new PromotionRankingItem("201", "42", "1001", 120L, 1))));
        PromotionSnapshotService service = new PromotionSnapshotService(
                windowMapper, allocationMapper, redisSnapshotAdapter);

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
        PromotionRedisSnapshotAdapter redisSnapshotAdapter = mock(PromotionRedisSnapshotAdapter.class);
        when(redisSnapshotAdapter.snapshot(301L)).thenReturn(new PromotionAuctionHotSnapshot(7L, List.of()));
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
        PromotionSnapshotService service = new PromotionSnapshotService(
                windowMapper, allocationMapper, redisSnapshotAdapter);

        PromotionAuctionSnapshot snapshot = service.snapshot(301L);

        assertThat(snapshot.status()).isEqualTo("SETTLED");
        assertThat(snapshot.ranking()).hasSize(1);
        assertThat(snapshot.ranking().get(0).bidAmount()).isEqualTo(100L);
    }
}
