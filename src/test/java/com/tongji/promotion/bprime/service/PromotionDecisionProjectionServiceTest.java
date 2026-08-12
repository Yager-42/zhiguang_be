package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionDecisionProjectionServiceTest {

    @Mock private PromotionProjectionCheckpointMapper checkpointMapper;
    @Mock private PromotionBidEscrowMapper escrowMapper;
    @Mock private PromotionBidMapper bidMapper;
    @Mock private PromotionAuctionWindowMapper windowMapper;
    @Mock private PromotionSlotAllocationMapper allocationMapper;
    @Mock private WalletService walletService;
    @Mock private PromotionAllocationCacheService cacheService;
    @Mock private IdService idService;

    private PromotionDecisionProjectionService service;

    @BeforeEach
    void setUp() {
        service = new PromotionDecisionProjectionService(
                checkpointMapper, escrowMapper, bidMapper, windowMapper, allocationMapper,
                walletService, cacheService, idService);
    }

    @Test
    void acceptedBidProjectsOnceAndPersistsStreamCheckpoint() {
        when(idService.nextId(any())).thenReturn(9001L);
        when(escrowMapper.updateCurrentHold(301L, 201L, 120L, DECIDED_AT)).thenReturn(1);

        List<PromotionAuctionDecision> projected = service.projectBatch(
                List.of(new PromotionDecisionProjectionItem(decision("d-1", 1L, 0L), "1-0")));

        assertThat(projected).hasSize(1);
        verify(bidMapper).upsertAccepted(any());
        verify(checkpointMapper).upsert(301L, "d-1", 1L, "1-0");
    }

    @Test
    void duplicateCheckpointDoesNotApplyBidAgain() {
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(301L);
        checkpoint.setLastDecisionId("d-1");
        checkpoint.setLastDecisionVersion(1L);
        checkpoint.setLastStreamId("1-0");
        when(checkpointMapper.findByAuctionWindowId(301L)).thenReturn(checkpoint);

        List<PromotionAuctionDecision> projected = service.projectBatch(
                List.of(new PromotionDecisionProjectionItem(decision("d-1", 1L, 0L), "1-0")));

        assertThat(projected).isEmpty();
        verify(bidMapper, never()).upsertAccepted(any());
        verify(checkpointMapper).upsert(301L, "d-1", 1L, "1-0");
    }

    @Test
    void stalePayloadAtNewStreamIdMustNotAdvanceCheckpoint() {
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(301L);
        checkpoint.setLastDecisionId("d-2");
        checkpoint.setLastDecisionVersion(2L);
        checkpoint.setLastStreamId("2-0");
        when(checkpointMapper.findByAuctionWindowId(301L)).thenReturn(checkpoint);

        assertThatThrownBy(() -> service.projectBatch(
                List.of(new PromotionDecisionProjectionItem(decision("d-1", 1L, 0L), "3-0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ID/version mismatch");

        verify(checkpointMapper, never()).upsert(anyLong(), any(), anyLong(), any());
    }

    @Test
    void versionGapMustNotAdvanceCheckpoint() {
        PromotionAuctionDecision gap = decision("d-2", 2L, 1L);

        assertThatThrownBy(() -> service.projectBatch(
                List.of(new PromotionDecisionProjectionItem(gap, "2-0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version gap");

        verify(checkpointMapper, never()).upsert(eq(301L), any(), eq(2L), any());
    }

    @Test
    void extendedDecisionAdvancesCheckpointOnly() {
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(301L);
        checkpoint.setLastDecisionId("d-1");
        checkpoint.setLastDecisionVersion(1L);
        checkpoint.setLastStreamId("1-0");
        when(checkpointMapper.findByAuctionWindowId(301L)).thenReturn(checkpoint);

        List<PromotionAuctionDecision> projected = service.projectBatch(List.of(
                new PromotionDecisionProjectionItem(
                        new PromotionAuctionDecision("d-ext", "cmd-1", "hash", 301L, 2L, 1L,
                                201L, 42L, 1001L, "FEED_TOP_SLOT", "AUCTION_EXTENDED", true, null,
                                120L, List.of(), List.of(),
                                Map.of("endAtEpochMs", 123L, "extendCount", 1), DECIDED_AT),
                        "2-0")));

        assertThat(projected).hasSize(1);
        verify(bidMapper, never()).upsertAccepted(any());
        verify(allocationMapper, never()).insert(any());
        verify(checkpointMapper).upsert(301L, "d-ext", 2L, "2-0");
    }

    @Test
    void soldWindowSettlesFirstPriceSingleAllocation() {
        when(checkpointMapper.findByAuctionWindowId(301L))
                .thenReturn(checkpoint("d-1", 1L, "1-0"));
        when(windowMapper.findById(301L)).thenReturn(window());
        when(bidMapper.listActiveBidsByWindowId(301L, WINDOW_END, ALLOCATION_END))
                .thenReturn(List.of(bid(201L, 42L, 300L), bid(202L, 43L, 250L)));
        when(escrowMapper.listActiveByWindowId(301L))
                .thenReturn(List.of(escrow(201L, 42L, 500L), escrow(202L, 43L, 400L)));
        when(idService.nextId(any())).thenReturn(9001L);

        service.projectBatch(List.of(new PromotionDecisionProjectionItem(
                new PromotionAuctionDecision("d-sold", "cmd-close", "hash", 301L, 2L, 1L,
                        201L, 42L, 1001L, "FEED_TOP_SLOT", "AUCTION_SOLD", true, null,
                        0L, List.of(), List.of(),
                        Map.of("winnerCampaignId", "201", "winningAmount", 300), DECIDED_AT),
                "2-0")));

        // winner 付第一价格 300，释放 500-300=200；loser 全释放 400
        verify(walletService).captureHoldToPlatform(eq(42L), eq(300L),
                eq(com.tongji.wallet.model.WalletLedgerReason.PROMOTION_BPRIME_CAPTURE),
                eq(com.tongji.wallet.model.WalletBusinessType.PROMOTION), any());
        verify(walletService).releaseHold(eq(42L), eq(200L), any(), any(), any());
        verify(walletService).releaseHold(eq(43L), eq(400L), any(), any(), any());
        verify(bidMapper).markWon(anyLong(), eq(0), eq(300L));
        verify(bidMapper).markLost(anyLong());
        ArgumentCaptor<com.tongji.promotion.model.PromotionSlotAllocation> allocation =
                ArgumentCaptor.forClass(com.tongji.promotion.model.PromotionSlotAllocation.class);
        verify(allocationMapper).insert(allocation.capture());
        assertThat(allocation.getValue().getSlotIndex()).isZero();
        assertThat(allocation.getValue().getClearingPrice()).isEqualTo(300L);
        assertThat(allocation.getValue().getCampaignId()).isEqualTo(201L);
        assertThat(allocation.getValue().getAllocationStartAt()).isEqualTo(WINDOW_END);
        verify(escrowMapper).markClosedByWindowId(301L, DECIDED_AT);
        verify(windowMapper).markSettled(301L, DECIDED_AT);
        verify(cacheService).refreshActiveAllocations(any(), eq(DECIDED_AT));
    }

    @Test
    void noBidWindowReleasesAllEscrowsWithoutAllocation() {
        when(checkpointMapper.findByAuctionWindowId(301L))
                .thenReturn(checkpoint("d-1", 1L, "1-0"));
        when(windowMapper.findById(301L)).thenReturn(window());
        when(bidMapper.listActiveBidsByWindowId(301L, WINDOW_END, ALLOCATION_END))
                .thenReturn(List.of());
        when(escrowMapper.listActiveByWindowId(301L))
                .thenReturn(List.of(escrow(201L, 42L, 500L)));

        service.projectBatch(List.of(new PromotionDecisionProjectionItem(
                new PromotionAuctionDecision("d-nobid", "cmd-close", "hash", 301L, 2L, 1L,
                        0L, 0L, 0L, "FEED_TOP_SLOT", "AUCTION_NO_BID", true, null,
                        0L, List.of(), List.of(),
                        Map.of("actualEndAtEpochMs", 123L), DECIDED_AT),
                "2-0")));

        verify(walletService).releaseHold(eq(42L), eq(500L), any(), any(), any());
        verify(walletService, never()).captureHoldToPlatform(anyLong(), anyLong(), any(), any(), any());
        verify(allocationMapper, never()).insert(any());
        verify(bidMapper, never()).markWon(anyLong(), anyInt(), anyLong());
        verify(escrowMapper).markClosedByWindowId(301L, DECIDED_AT);
        verify(windowMapper).markSettled(301L, DECIDED_AT);
    }

    @Test
    void soldWinnerMismatchWithRankedTopFailsFast() {
        when(checkpointMapper.findByAuctionWindowId(301L))
                .thenReturn(checkpoint("d-1", 1L, "1-0"));
        when(windowMapper.findById(301L)).thenReturn(window());
        when(bidMapper.listActiveBidsByWindowId(301L, WINDOW_END, ALLOCATION_END))
                .thenReturn(List.of(bid(201L, 42L, 300L)));
        when(escrowMapper.listActiveByWindowId(301L))
                .thenReturn(List.of(escrow(201L, 42L, 500L)));

        assertThatThrownBy(() -> service.projectBatch(List.of(new PromotionDecisionProjectionItem(
                new PromotionAuctionDecision("d-sold", "cmd-close", "hash", 301L, 2L, 1L,
                        202L, 43L, 1002L, "FEED_TOP_SLOT", "AUCTION_SOLD", true, null,
                        0L, List.of(), List.of(),
                        Map.of("winnerCampaignId", "202", "winningAmount", 300), DECIDED_AT),
                "2-0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("winner mismatch");
    }

    private PromotionProjectionCheckpointRecord checkpoint(String id, long version, String streamId) {
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(301L);
        checkpoint.setLastDecisionId(id);
        checkpoint.setLastDecisionVersion(version);
        checkpoint.setLastStreamId(streamId);
        return checkpoint;
    }

    private static final Instant WINDOW_START = Instant.parse("2026-06-20T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant ALLOCATION_END = Instant.parse("2026-06-20T11:00:00Z");

    private com.tongji.promotion.model.PromotionAuctionWindow window() {
        return com.tongji.promotion.model.PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(com.tongji.promotion.model.PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(WINDOW_START)
                .windowEndAt(WINDOW_END)
                .build();
    }

    private com.tongji.promotion.model.PromotionBid bid(long campaignId, long bidderUserId, long amount) {
        return com.tongji.promotion.model.PromotionBid.builder()
                .id(campaignId)
                .campaignId(campaignId)
                .bidderUserId(bidderUserId)
                .postId(1000L + bidderUserId)
                .bidAmount(amount)
                .build();
    }

    private com.tongji.promotion.bprime.model.PromotionBidEscrowRecord escrow(
            long campaignId, long bidderUserId, long authorizedAmount) {
        return com.tongji.promotion.bprime.model.PromotionBidEscrowRecord.builder()
                .id(campaignId)
                .auctionWindowId(301L)
                .campaignId(campaignId)
                .bidderUserId(bidderUserId)
                .authorizedAmount(authorizedAmount)
                .build();
    }

    private PromotionAuctionDecision decision(String id, long version, long previousVersion) {
        return new PromotionAuctionDecision(
                id, "cmd-1", "hash", 301L, version, previousVersion,
                201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true, null,
                120L, List.of(), List.of(), Map.of("authorizedAmount", 500L), DECIDED_AT);
    }

    private static final Instant DECIDED_AT = Instant.parse("2026-06-20T10:05:00Z");
}
