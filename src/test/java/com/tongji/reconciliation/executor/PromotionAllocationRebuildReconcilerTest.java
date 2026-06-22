package com.tongji.reconciliation.executor;

import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAuctionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAllocationRebuildReconcilerTest {

    @Test
    void rebuildsAllocationBySettlingProjectedBidFacts() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionSlotAllocationMapper allocationMapper = mock(PromotionSlotAllocationMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(50L)
                .status(PromotionAuctionWindowStatus.SETTLED)
                .settledAt(Instant.parse("2026-06-20T11:00:01Z"))
                .build();
        List<PromotionBid> bids = List.of(PromotionBid.builder().id(401L).campaignId(201L).build());
        when(windowMapper.findById(301L)).thenReturn(window);
        when(allocationMapper.countByAuctionWindowId(301L)).thenReturn(0);
        when(bidMapper.listActiveBidsByWindowId(eq(301L), eq(Instant.parse("2026-06-20T11:00:00Z")),
                eq(Instant.parse("2026-06-20T12:00:00Z")))).thenReturn(bids);
        PromotionAllocationRebuildReconciler reconciler = new PromotionAllocationRebuildReconciler(
                windowMapper, bidMapper, allocationMapper, auctionService);

        reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW)
                .targetId(301L)
                .build());

        verify(auctionService).settleWindow(window, bids, Instant.parse("2026-06-20T11:00:01Z"));
    }
}
