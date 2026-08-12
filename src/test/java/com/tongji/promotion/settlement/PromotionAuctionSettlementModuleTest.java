package com.tongji.promotion.settlement;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionSettlementModuleTest {

    @Mock PromotionAuctionWindowMapper windowMapper;
    @Mock PromotionBidMapper bidMapper;
    @Mock PromotionBidEscrowMapper escrowMapper;
    @Mock PromotionSlotAllocationMapper allocationMapper;
    @Mock WalletService walletService;
    @Mock IdService idService;
    @Mock PromotionAllocationCacheService cacheService;

    private PromotionAuctionSettlementModule module;

    @BeforeEach
    void setUp() {
        module = new PromotionAuctionSettlementModule(windowMapper, bidMapper, escrowMapper, allocationMapper,
                walletService, idService, cacheService, new WalletProperties());
    }

    @Test
    void soldSettlementCapturesFirstPriceAndReleasesAllAuthorizationSurplus() {
        PromotionAuctionWindow window = window();
        PromotionBid winner = bid(401L, 201L, 42L, 120L);
        PromotionBid loser = bid(402L, 202L, 43L, 100L);
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listActiveBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(winner, loser));
        when(escrowMapper.listActiveByWindowId(301L)).thenReturn(List.of(
                escrow(601L, 201L, 42L, 150L),
                escrow(602L, 202L, 43L, 100L),
                escrow(603L, 203L, 44L, 70L)));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L);
        when(windowMapper.markSettledIfOpen(301L, Instant.parse("2026-06-20T11:00:01Z"))).thenReturn(1);

        PromotionAuctionSettlementFacts facts = module.settle(new PromotionAuctionTerminalInput(
                301L,
                PromotionAuctionTerminalInput.Kind.SOLD,
                201L,
                120L,
                Instant.parse("2026-06-20T11:00:01Z")));

        assertThat(facts.terminalKind()).isEqualTo(PromotionAuctionSettlementFacts.TerminalKind.SOLD);
        assertThat(facts.winner().orElseThrow().campaignId()).isEqualTo(201L);
        assertThat(facts.winner().orElseThrow().winningAmount()).isEqualTo(120L);
        assertThat(facts.walletEffects())
                .extracting(PromotionAuctionSettlementFacts.WalletEffect::businessRef,
                        PromotionAuctionSettlementFacts.WalletEffect::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:201:capture", 120L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:201:release", 30L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:202:release", 100L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:203:release", 70L));
        assertThat(facts.allocation().orElseThrow().allocationStartAt())
                .isEqualTo(Instant.parse("2026-06-20T11:00:00Z"));
        assertThat(facts.allocation().orElseThrow().allocationEndAt())
                .isEqualTo(Instant.parse("2026-06-20T12:00:00Z"));
    }

    @Test
    void matchingDuplicateSettlementReturnsExistingFactsWithoutWrites() {
        PromotionAuctionWindow window = window();
        window.setStatus(PromotionAuctionWindowStatus.SETTLED);
        PromotionBid winner = bid(401L, 201L, 42L, 120L);
        winner.setStatus(PromotionBidStatus.WON);
        winner.setClearingPrice(120L);
        winner.setSlotIndex(0);
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listSettledBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(winner));
        when(escrowMapper.listByWindowId(301L)).thenReturn(List.of(closedEscrow(601L, 201L, 42L, 150L)));

        PromotionAuctionSettlementFacts facts = module.settle(new PromotionAuctionTerminalInput(
                301L, PromotionAuctionTerminalInput.Kind.SOLD, 201L, 120L,
                Instant.parse("2026-06-20T11:00:02Z")));

        assertThat(facts.winner().orElseThrow().campaignId()).isEqualTo(201L);
        org.mockito.Mockito.verifyNoInteractions(walletService);
        org.mockito.Mockito.verify(allocationMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noBidReleasesEveryAuthorizationWithoutAllocation() {
        PromotionAuctionWindow window = window();
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listActiveBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of());
        when(escrowMapper.listActiveByWindowId(301L)).thenReturn(List.of(
                escrow(601L, 201L, 42L, 150L),
                escrow(602L, 202L, 43L, 100L)));
        when(windowMapper.markSettledIfOpen(301L, Instant.parse("2026-06-20T11:00:01Z"))).thenReturn(1);

        PromotionAuctionSettlementFacts facts = module.settle(new PromotionAuctionTerminalInput(
                301L,
                PromotionAuctionTerminalInput.Kind.NO_BID,
                null,
                null,
                Instant.parse("2026-06-20T11:00:01Z")));

        assertThat(facts.terminalKind()).isEqualTo(PromotionAuctionSettlementFacts.TerminalKind.NO_BID);
        assertThat(facts.winner()).isEmpty();
        assertThat(facts.allocation()).isEmpty();
        assertThat(facts.walletEffects())
                .extracting(PromotionAuctionSettlementFacts.WalletEffect::businessRef,
                        PromotionAuctionSettlementFacts.WalletEffect::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:201:release", 150L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:202:release", 100L));
    }

    @Test
    void soldSettlementRejectsTerminalWinnerConflict() {
        PromotionAuctionWindow window = window();
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listActiveBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(
                bid(401L, 201L, 42L, 120L), bid(402L, 202L, 43L, 100L)));
        when(escrowMapper.listActiveByWindowId(301L)).thenReturn(List.of(
                escrow(601L, 201L, 42L, 150L), escrow(602L, 202L, 43L, 100L)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> module.settle(new PromotionAuctionTerminalInput(
                        301L, PromotionAuctionTerminalInput.Kind.SOLD, 202L, 100L,
                        Instant.parse("2026-06-20T11:00:01Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("winner mismatch");
    }

    @Test
    void soldSettlementRejectsBidWithoutActiveEscrow() {
        PromotionAuctionWindow window = window();
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listActiveBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(bid(401L, 201L, 42L, 120L)));
        when(escrowMapper.listActiveByWindowId(301L)).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> module.settle(new PromotionAuctionTerminalInput(
                        301L, PromotionAuctionTerminalInput.Kind.SOLD, 201L, 120L,
                        Instant.parse("2026-06-20T11:00:01Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matching active escrow");
    }

    @Test
    void soldSettlementRejectsInsufficientWinnerAuthorization() {
        PromotionAuctionWindow window = window();
        when(windowMapper.findByIdForUpdate(301L)).thenReturn(window);
        when(bidMapper.listActiveBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(bid(401L, 201L, 42L, 120L)));
        when(escrowMapper.listActiveByWindowId(301L)).thenReturn(List.of(escrow(601L, 201L, 42L, 119L)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> module.settle(new PromotionAuctionTerminalInput(
                        301L, PromotionAuctionTerminalInput.Kind.SOLD, 201L, 120L,
                        Instant.parse("2026-06-20T11:00:01Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorization is insufficient");
    }

    @Test
    void expectedSettlementComesFromSettledMysqlFacts() {
        PromotionAuctionWindow window = window();
        window.setStatus(PromotionAuctionWindowStatus.SETTLED);
        PromotionBid winner = bid(401L, 201L, 42L, 120L);
        winner.setStatus(PromotionBidStatus.WON);
        winner.setClearingPrice(120L);
        winner.setSlotIndex(0);
        PromotionBid loser = bid(402L, 202L, 43L, 100L);
        loser.setStatus(PromotionBidStatus.LOST);
        when(windowMapper.findById(301L)).thenReturn(window);
        when(bidMapper.listSettledBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(winner, loser));
        when(escrowMapper.listByWindowId(301L)).thenReturn(List.of(
                closedEscrow(601L, 201L, 42L, 150L), closedEscrow(602L, 202L, 43L, 100L)));

        PromotionAuctionSettlementFacts facts = module.expectedSettlement(301L);

        assertThat(facts.winner().orElseThrow().campaignId()).isEqualTo(201L);
        assertThat(facts.walletEffects())
                .extracting(PromotionAuctionSettlementFacts.WalletEffect::businessRef,
                        PromotionAuctionSettlementFacts.WalletEffect::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:201:capture", 120L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:201:release", 30L),
                        org.assertj.core.groups.Tuple.tuple("promotion-bprime:301:202:release", 100L));
    }

    @Test
    void expectedSettlementRejectsMissingEscrowFacts() {
        PromotionAuctionWindow window = window();
        window.setStatus(PromotionAuctionWindowStatus.SETTLED);
        PromotionBid winner = bid(401L, 201L, 42L, 120L);
        winner.setStatus(PromotionBidStatus.WON);
        winner.setClearingPrice(120L);
        winner.setSlotIndex(0);
        when(windowMapper.findById(301L)).thenReturn(window);
        when(bidMapper.listSettledBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(winner));
        when(escrowMapper.listByWindowId(301L)).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> module.expectedSettlement(301L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matching active escrow");
    }

    @Test
    void allocationRebuildInsertsOnlyWhollyMissingAllocation() {
        PromotionAuctionWindow window = window();
        window.setStatus(PromotionAuctionWindowStatus.SETTLED);
        PromotionBid winner = bid(401L, 201L, 42L, 120L);
        winner.setStatus(PromotionBidStatus.WON);
        winner.setClearingPrice(120L);
        winner.setSlotIndex(0);
        when(windowMapper.findById(301L)).thenReturn(window);
        when(bidMapper.listSettledBidsByWindowId(301L, Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(List.of(winner));
        when(escrowMapper.listByWindowId(301L)).thenReturn(List.of(closedEscrow(601L, 201L, 42L, 150L)));
        when(allocationMapper.countByAuctionWindowId(301L)).thenReturn(0);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L);

        PromotionAuctionSettlementFacts facts = module.rebuildMissingAllocation(301L);

        assertThat(facts.allocation()).isPresent();
        org.mockito.Mockito.verify(allocationMapper).insert(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(walletService);
        org.mockito.Mockito.verify(bidMapper, org.mockito.Mockito.never())
                .markWon(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(escrowMapper, org.mockito.Mockito.never())
                .markClosedByWindowId(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(windowMapper, org.mockito.Mockito.never())
                .markSettledIfOpen(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private PromotionBidEscrowRecord closedEscrow(long id, long campaignId, long bidderUserId,
                                                   long authorizedAmount) {
        PromotionBidEscrowRecord record = escrow(id, campaignId, bidderUserId, authorizedAmount);
        record.setCurrentHold(0L);
        record.setStatus("CLOSED");
        return record;
    }

    private PromotionAuctionWindow window() {
        return PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(50L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
    }

    private PromotionBid bid(long id, long campaignId, long bidderUserId, long amount) {
        return PromotionBid.builder()
                .id(id)
                .campaignId(campaignId)
                .auctionWindowId(301L)
                .bidderUserId(bidderUserId)
                .bidAmount(amount)
                .postId(5000L + campaignId)
                .status(PromotionBidStatus.ACTIVE)
                .build();
    }

    private PromotionBidEscrowRecord escrow(long id, long campaignId, long bidderUserId, long authorizedAmount) {
        return PromotionBidEscrowRecord.builder()
                .id(id)
                .auctionWindowId(301L)
                .campaignId(campaignId)
                .bidderUserId(bidderUserId)
                .authorizedAmount(authorizedAmount)
                .currentHold(authorizedAmount)
                .status("ACTIVE")
                .build();
    }
}
