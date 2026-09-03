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
import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.promotion.settlement.PromotionAuctionTerminalInput;
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
    @Mock private PromotionAuctionSettlementModule settlementModule;

    private PromotionDecisionProjectionService service;

    @BeforeEach
    void setUp() {
        service = new PromotionDecisionProjectionService(
                checkpointMapper, escrowMapper, bidMapper, settlementModule, idService);
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
    void soldTerminalDelegatesStronglyTypedInputAndAdvancesCheckpoint() {
        when(checkpointMapper.findByAuctionWindowId(301L))
                .thenReturn(checkpoint("d-1", 1L, "1-0"));

        service.projectBatch(List.of(new PromotionDecisionProjectionItem(
                new PromotionAuctionDecision("d-sold", "cmd-close", "hash", 301L, 2L, 1L,
                        201L, 42L, 1001L, "FEED_TOP_SLOT", "AUCTION_SOLD", true, null,
                        0L, List.of(), List.of(),
                        Map.of("winnerCampaignId", "201", "winningAmount", 300), DECIDED_AT),
                "2-0")));

        verify(settlementModule).settle(new PromotionAuctionTerminalInput(
                301L, PromotionAuctionTerminalInput.Kind.SOLD, 201L, 300L, DECIDED_AT));
        verify(checkpointMapper).upsert(301L, "d-sold", 2L, "2-0");
    }

    @Test
    void noBidTerminalDelegatesWithoutWinnerAndAdvancesCheckpoint() {
        when(checkpointMapper.findByAuctionWindowId(301L))
                .thenReturn(checkpoint("d-1", 1L, "1-0"));

        service.projectBatch(List.of(new PromotionDecisionProjectionItem(
                new PromotionAuctionDecision("d-nobid", "cmd-close", "hash", 301L, 2L, 1L,
                        0L, 0L, 0L, "FEED_TOP_SLOT", "AUCTION_NO_BID", true, null,
                        0L, List.of(), List.of(), Map.of(), DECIDED_AT), "2-0")));

        verify(settlementModule).settle(new PromotionAuctionTerminalInput(
                301L, PromotionAuctionTerminalInput.Kind.NO_BID, null, null, DECIDED_AT));
        verify(checkpointMapper).upsert(301L, "d-nobid", 2L, "2-0");
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
