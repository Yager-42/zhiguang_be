package com.tongji.promotion.bprime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionDecisionProjectionServiceTest {

    @Test
    void acceptedDecisionStoresDecisionBidAndCheckpoint() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        when(decisionMapper.insertIgnore(any())).thenReturn(1);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L, 2L);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(decision(true));

        ArgumentCaptor<PromotionBid> bidCaptor = ArgumentCaptor.forClass(PromotionBid.class);
        verify(bidMapper).upsertAccepted(bidCaptor.capture());
        assertThat(bidCaptor.getValue().getCommandId()).isEqualTo("cmd-1");
        assertThat(bidCaptor.getValue().getWalletBusinessRef()).isEqualTo("promotion-bprime:cmd-1:hold");
        verify(checkpointMapper).upsert(301L, "d-1", null, null, null);
    }

    @Test
    void acceptedDecisionUsesLoggedHoldEffectBusinessRefForProjectedBid() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        when(decisionMapper.insertIgnore(any())).thenReturn(1);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L, 2L);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(),
                List.of(new com.tongji.promotion.bprime.model.PromotionWalletEffect(
                        42L, 120L, "HOLD", "promotion-bprime:cmd-1:hold:retry")),
                Instant.parse("2026-06-20T10:05:00Z")));

        ArgumentCaptor<PromotionBid> bidCaptor = ArgumentCaptor.forClass(PromotionBid.class);
        verify(bidMapper).upsertAccepted(bidCaptor.capture());
        assertThat(bidCaptor.getValue().getWalletBusinessRef()).isEqualTo("promotion-bprime:cmd-1:hold:retry");
    }

    @Test
    void rejectedDecisionStoresNoBid() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        when(decisionMapper.insertIgnore(any())).thenReturn(1);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(decision(false));

        org.mockito.Mockito.verifyNoInteractions(bidMapper);
        verify(checkpointMapper).upsert(301L, "d-1", null, null, null);
    }

    @Test
    void windowClosedDecisionSettlesFromProjectedBidFacts() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(2)
                .reservePrice(50L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);
        when(decisionMapper.insertIgnore(any())).thenReturn(1);
        when(windowMapper.findById(301L)).thenReturn(window);
        List<PromotionBid> bids = List.of(PromotionBid.builder().id(401L).campaignId(201L).build());
        when(bidMapper.listActiveBidsByWindowId(eq(301L), eq(Instant.parse("2026-06-20T11:00:00Z")),
                eq(Instant.parse("2026-06-20T12:00:00Z")))).thenReturn(bids);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(new PromotionAuctionDecision("close-1", "close-cmd", "hash", 301L, 0L, 0L, 0L,
                "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L, List.of(), List.of(),
                Instant.parse("2026-06-20T11:00:00Z")));

        verify(auctionService).settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(checkpointMapper).upsert(301L, "close-1", null, null, null);
    }

    @Test
    void duplicateWindowClosedDecisionDoesNotSettleAgain() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);
        when(decisionMapper.insertIgnore(any())).thenReturn(0);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(new PromotionAuctionDecision("close-1", "close-cmd", "hash", 301L, 0L, 0L, 0L,
                "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L, List.of(), List.of(),
                Instant.parse("2026-06-20T11:00:00Z")));

        org.mockito.Mockito.verifyNoInteractions(windowMapper, bidMapper, auctionService, cacheService);
        verify(checkpointMapper).upsert(301L, "close-1", null, null, null);
    }

    @Test
    void projectWithKafkaPositionStoresOffsetCheckpoint() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        PromotionBidMapper bidMapper = mock(PromotionBidMapper.class);
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        PromotionAuctionService auctionService = mock(PromotionAuctionService.class);
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        IdService idService = mock(IdService.class);
        when(decisionMapper.insertIgnore(any())).thenReturn(1);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);
        PromotionDecisionProjectionService service = new PromotionDecisionProjectionService(
                decisionMapper, checkpointMapper, bidMapper, windowMapper, auctionService,
                cacheService,
                new ObjectMapper().findAndRegisterModules(), idService);

        service.project(decision(false), "decisions.v2", 3, 99L);

        verify(checkpointMapper).upsert(301L, "d-1", "decisions.v2", 3, 99L);
    }

    private PromotionAuctionDecision decision(boolean accepted) {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", accepted ? "BID_ACCEPTED" : "BID_REJECTED", accepted,
                accepted ? null : "BELOW_RESERVE", 120L, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
