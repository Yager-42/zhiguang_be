package com.tongji.reconciliation.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAuctionSettlementPlanner;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PromotionAuctionCompensationServiceTest {

    private PromotionBidMapper bidMapper;
    private PromotionSlotAllocationMapper allocationMapper;
    private WalletLedgerMapper walletLedgerMapper;
    private ReconciliationService reconciliationService;
    private PromotionAuctionCompensationService service;

    @BeforeEach
    void setUp() {
        bidMapper = mock(PromotionBidMapper.class);
        allocationMapper = mock(PromotionSlotAllocationMapper.class);
        walletLedgerMapper = mock(WalletLedgerMapper.class);
        reconciliationService = mock(ReconciliationService.class);
        service = new PromotionAuctionCompensationService(
                bidMapper,
                allocationMapper,
                walletLedgerMapper,
                new PromotionAuctionSettlementPlanner(new WalletProperties()),
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void scanWindowCreatesAllocationAndWalletRepairTasksWhenFactsAreMissing() {
        PromotionAuctionWindow window = window();
        when(bidMapper.listSettledBidsByWindowId(eq(301L), eq(Instant.parse("2026-06-20T11:00:00Z")),
                eq(Instant.parse("2026-06-20T12:00:00Z"))))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L, PromotionBidStatus.WON),
                        bid(402L, 202L, 43L, 80L, PromotionBidStatus.LOST)));
        when(allocationMapper.listByAuctionWindowId(301L)).thenReturn(List.of());
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:201:capture")).thenReturn(List.of());
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:201:release")).thenReturn(List.of());
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:202:release")).thenReturn(List.of());

        service.scanWindow(window, reconciliationService);

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                301L
        );
        verify(reconciliationService).createTaskIfAbsent(
                eq(ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR),
                eq(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW),
                eq(301L),
                contains("\"effectType\":\"CAPTURE\"")
        );
        verify(reconciliationService, times(2)).createTaskIfAbsent(
                eq(ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR),
                eq(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW),
                eq(301L),
                contains("\"effectType\":\"RELEASE\"")
        );
    }

    @Test
    void scanWindowMarksPartialAllocationDead() {
        PromotionAuctionWindow window = window();
        when(bidMapper.listSettledBidsByWindowId(eq(301L), eq(Instant.parse("2026-06-20T11:00:00Z")),
                eq(Instant.parse("2026-06-20T12:00:00Z"))))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L, PromotionBidStatus.WON),
                        bid(402L, 202L, 43L, 80L, PromotionBidStatus.LOST)));
        when(allocationMapper.listByAuctionWindowId(301L)).thenReturn(List.of(allocation(0, 999L, 42L, 80L)));
        when(walletLedgerMapper.findByBusinessRef(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        service.scanWindow(window, reconciliationService);

        verify(reconciliationService).createDeadTaskIfAbsent(
                ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                301L,
                "promotion allocation is partially present or inconsistent for settled window 301"
        );
        verify(reconciliationService, never()).createTaskIfAbsent(
                ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                301L
        );
    }

    @Test
    void scanWindowMarksWalletConflictDead() {
        PromotionAuctionWindow window = window();
        when(bidMapper.listSettledBidsByWindowId(eq(301L), eq(Instant.parse("2026-06-20T11:00:00Z")),
                eq(Instant.parse("2026-06-20T12:00:00Z"))))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L, PromotionBidStatus.WON)));
        when(allocationMapper.listByAuctionWindowId(301L)).thenReturn(List.of(allocation(0, 201L, 42L, 50L)));
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:201:capture"))
                .thenReturn(List.of(WalletLedgerEntry.builder()
                        .ownerUserId(99L)
                        .amount(50L)
                        .reason(WalletLedgerReason.PROMOTION_BPRIME_CAPTURE)
                        .businessType(WalletBusinessType.PROMOTION)
                        .direction(WalletLedgerDirection.DEBIT)
                        .heldDelta(-50L)
                        .businessRef("promotion-bprime:301:201:capture")
                        .build()));
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:201:release")).thenReturn(List.of());

        service.scanWindow(window, reconciliationService);

        verify(reconciliationService).createDeadTaskIfAbsent(
                eq(ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR),
                eq(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW),
                eq(301L),
                contains("\"businessRef\":\"promotion-bprime:301:201:capture\""),
                contains("conflicts")
        );
    }

    private PromotionAuctionWindow window() {
        return PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(50L)
                .status(PromotionAuctionWindowStatus.SETTLED)
                .settledAt(Instant.parse("2026-06-20T11:00:01Z"))
                .build();
    }

    private PromotionBid bid(long id, long campaignId, long bidderUserId, long bidAmount, PromotionBidStatus status) {
        return PromotionBid.builder()
                .id(id)
                .campaignId(campaignId)
                .auctionWindowId(301L)
                .bidderUserId(bidderUserId)
                .bidAmount(bidAmount)
                .status(status)
                .postId(9000L + id)
                .build();
    }

    private PromotionSlotAllocation allocation(int slotIndex, long campaignId, long bidderUserId, long clearingPrice) {
        return PromotionSlotAllocation.builder()
                .auctionWindowId(301L)
                .slotIndex(slotIndex)
                .campaignId(campaignId)
                .bidderUserId(bidderUserId)
                .clearingPrice(clearingPrice)
                .build();
    }
}
