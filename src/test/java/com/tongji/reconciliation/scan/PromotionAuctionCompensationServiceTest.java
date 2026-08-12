package com.tongji.reconciliation.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.settlement.PromotionAuctionSettlementFacts;
import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAuctionCompensationServiceTest {

    private PromotionSlotAllocationMapper allocationMapper;
    private WalletLedgerMapper walletLedgerMapper;
    private PromotionAuctionSettlementModule settlementModule;
    private ReconciliationService reconciliationService;
    private PromotionAuctionCompensationService service;

    @BeforeEach
    void setUp() {
        allocationMapper = mock(PromotionSlotAllocationMapper.class);
        walletLedgerMapper = mock(WalletLedgerMapper.class);
        settlementModule = mock(PromotionAuctionSettlementModule.class);
        reconciliationService = mock(ReconciliationService.class);
        service = new PromotionAuctionCompensationService(allocationMapper, walletLedgerMapper,
                settlementModule, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void sharedFactsCreateAllocationAndWalletRepairTasks() {
        PromotionAuctionWindow window = window();
        when(settlementModule.expectedSettlement(301L)).thenReturn(facts(window));
        when(allocationMapper.listByAuctionWindowId(301L)).thenReturn(List.of());
        when(walletLedgerMapper.findByBusinessRef("promotion-bprime:301:201:capture")).thenReturn(List.of());

        service.scanWindow(window, reconciliationService);

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                ReconciliationTargetType.PROMOTION_AUCTION_WINDOW, 301L);
        verify(reconciliationService).createTaskIfAbsent(
                eq(ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR),
                eq(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW), eq(301L),
                contains("\"businessRef\":\"promotion-bprime:301:201:capture\""));
    }

    private PromotionAuctionSettlementFacts facts(PromotionAuctionWindow window) {
        PromotionBid bid = PromotionBid.builder().id(401L).campaignId(201L).bidderUserId(42L)
                .postId(1001L).bidAmount(120L).build();
        return new PromotionAuctionSettlementFacts(window, PromotionAuctionSettlementFacts.TerminalKind.SOLD,
                Optional.of(new PromotionAuctionSettlementFacts.Winner(bid, 201L, 120L)), List.of(),
                List.of(new PromotionAuctionSettlementFacts.WalletEffect(
                        PromotionAuctionSettlementFacts.EffectType.CAPTURE, 42L, 120L,
                        WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                        WalletLedgerDirection.DEBIT, 0L, null, 0L, -120L, 0L,
                        "promotion-bprime:301:201:capture")),
                Optional.of(new PromotionAuctionSettlementFacts.Allocation(
                        PromotionResourceType.FEED_TOP_SLOT, 0, 201L, 1001L, 42L, 120L,
                        Instant.parse("2026-06-20T11:00:00Z"), Instant.parse("2026-06-20T12:00:00Z"))));
    }

    private PromotionAuctionWindow window() {
        return PromotionAuctionWindow.builder().id(301L).resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .status(PromotionAuctionWindowStatus.SETTLED)
                .settledAt(Instant.parse("2026-06-20T11:00:01Z")).build();
    }
}
