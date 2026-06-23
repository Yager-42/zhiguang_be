package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PromotionDecisionProjectionServiceTest {

    private PromotionProjectionCheckpointMapper checkpointMapper;
    private PromotionBidMapper bidMapper;
    private PromotionAuctionWindowMapper windowMapper;
    private PromotionSlotAllocationMapper allocationMapper;
    private PromotionAllocationCacheService cacheService;
    private IdService idService;
    private WalletService walletService;
    private StringRedisTemplate redisTemplate;
    private PromotionDecisionProjectionService service;

    @BeforeEach
    void setUp() {
        checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        bidMapper = mock(PromotionBidMapper.class);
        windowMapper = mock(PromotionAuctionWindowMapper.class);
        allocationMapper = mock(PromotionSlotAllocationMapper.class);
        cacheService = mock(PromotionAllocationCacheService.class);
        idService = mock(IdService.class);
        walletService = mock(WalletService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        service = new PromotionDecisionProjectionService(checkpointMapper, bidMapper, windowMapper,
                allocationMapper, walletService, cacheService, idService, redisTemplate);
    }

    @Test
    void acceptedDecisionStoresBidAndCheckpointOnly() {
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);

        service.project(decision(true));

        ArgumentCaptor<PromotionBid> bidCaptor = ArgumentCaptor.forClass(PromotionBid.class);
        verify(bidMapper).upsertAccepted(bidCaptor.capture());
        assertThat(bidCaptor.getValue().getCommandId()).isEqualTo("cmd-1");
        assertThat(bidCaptor.getValue().getWalletBusinessRef()).isEqualTo("promotion-bprime:cmd-1:hold");
        verify(checkpointMapper).upsert(301L, "d-1", 1L, null, null, null);
    }

    @Test
    void acceptedDecisionUsesLoggedHoldEffectBusinessRefForProjectedBid() {
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);

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
    void rejectedDecisionAdvancesCheckpointOnly() {
        service.project(decision(false));

        verifyNoInteractions(bidMapper, windowMapper, allocationMapper, walletService, cacheService);
        verify(checkpointMapper).upsert(301L, "d-1", 1L, null, null, null);
    }

    @Test
    void duplicateWindowClosedDecisionDoesNotSettleAgainWhenAllocationExists() {
        when(allocationMapper.countByAuctionWindowId(301L)).thenReturn(1);

        service.project(closeDecision());

        verifyNoInteractions(windowMapper, bidMapper, walletService, cacheService);
        verify(checkpointMapper).upsert(301L, "close-1", 1L, null, null, null);
    }

    @Test
    void windowClosedDecisionProjectsFinalAllocationFromKafkaPayloadWithoutPriorMysqlBidRows() {
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(7001L);
        when(checkpointMapper.findLastDecisionVersion(301L)).thenReturn(5L);
        PromotionAuctionDecision closeDecision = closeDecisionWithPayload();

        assertThatCode(() -> service.project(closeDecision)).doesNotThrowAnyException();

        verifyNoInteractions(bidMapper);
        ArgumentCaptor<PromotionSlotAllocation> allocationCaptor = ArgumentCaptor.forClass(PromotionSlotAllocation.class);
        verify(allocationMapper).insert(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue().getCampaignId()).isEqualTo(201L);
        assertThat(allocationCaptor.getValue().getClearingPrice()).isEqualTo(100L);
        assertThat(allocationCaptor.getValue().getAllocationStartAt())
                .isEqualTo(Instant.parse("2026-06-20T11:00:00Z"));
        verify(walletService).captureHoldToPlatform(42L, 100L,
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                "promotion-bprime:301:201:capture");
        verify(walletService).releaseHold(43L, 80L,
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                "promotion-bprime:301:202:release");
        verify(windowMapper).markSettled(301L, Instant.parse("2026-06-20T11:00:00Z"));
        verify(redisTemplate).delete("promotion:auction:301:close_decision_version");
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(checkpointMapper).upsert(301L, "close-1", 6L, null, null, null);
    }

    @Test
    void windowClosedDecisionUsesPayloadWalletEffectsAsSettlementFacts() {
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(7001L);
        when(checkpointMapper.findLastDecisionVersion(301L)).thenReturn(5L);
        PromotionAuctionDecision closeDecision = new PromotionAuctionDecision("close-1", "close-cmd", "hash",
                301L, 6L, 5L, 0L, 0L, 0L, "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L,
                List.of(),
                List.of(new PromotionWalletEffect(99L, 1L, "CAPTURE", "top-level-effect-must-not-drive-settlement")),
                Map.of(
                        "winners", List.of(Map.of(
                                "campaignId", 201L,
                                "bidderUserId", 42L,
                                "postId", 1001L,
                                "slotIndex", 0,
                                "clearingPrice", 100L)),
                        "walletEffects", List.of(Map.of(
                                "ownerUserId", 42L,
                                "amount", 100L,
                                "effectType", "CAPTURE",
                                "businessRef", "promotion-bprime:301:201:capture")),
                        "allocationStartAt", "2026-06-20T11:00:00Z",
                        "allocationEndAt", "2026-06-20T12:00:00Z",
                        "finalWindowStatus", "SETTLED"),
                Instant.parse("2026-06-20T11:00:00Z"));

        service.project(closeDecision);

        verify(walletService).captureHoldToPlatform(42L, 100L,
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                "promotion-bprime:301:201:capture");
    }

    @Test
    void projectWithKafkaPositionStoresOffsetCheckpoint() {
        service.project(decision(false), "decisions.v2", 3, 99L);

        verify(checkpointMapper).upsert(301L, "d-1", 1L, "decisions.v2", 3, 99L);
    }

    @Test
    void rejectsOutOfOrderOrSkippedDecisionVersionBeforeProjectionSideEffects() {
        when(checkpointMapper.findLastDecisionVersion(301L)).thenReturn(1L);

        assertThatThrownBy(() -> service.project(new PromotionAuctionDecision("d-gap", "cmd-gap", "hash",
                301L, 3L, 2L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 120L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:06:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion decision version gap");

        verify(bidMapper, never()).upsertAccepted(any());
        verify(checkpointMapper, never()).upsert(301L, "d-gap", 3L, null, null, null);
    }

    @Test
    void duplicateDecisionRefreshesCheckpointPositionWithoutBusinessSideEffects() {
        when(checkpointMapper.findLastDecisionVersion(301L)).thenReturn(1L);
        when(checkpointMapper.findLastDecisionId(301L)).thenReturn("d-1");

        service.project(decision(true), "decisions.v2", 3, 101L);

        verify(bidMapper, never()).upsertAccepted(any());
        verify(checkpointMapper).upsert(301L, "d-1", 1L, "decisions.v2", 3, 101L);
    }

    private PromotionAuctionDecision decision(boolean accepted) {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", accepted ? "BID_ACCEPTED" : "BID_REJECTED", accepted,
                accepted ? null : "BELOW_RESERVE", 120L, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }

    private PromotionAuctionDecision closeDecision() {
        return new PromotionAuctionDecision("close-1", "close-cmd", "hash", 301L, 0L, 0L, 0L,
                "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L, List.of(), List.of(),
                Instant.parse("2026-06-20T11:00:00Z"));
    }

    private PromotionAuctionDecision closeDecisionWithPayload() {
        return new PromotionAuctionDecision("close-1", "close-cmd", "hash",
                301L, 6L, 5L, 0L, 0L, 0L, "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L,
                List.of(), List.of(
                new PromotionWalletEffect(42L, 100L, "CAPTURE", "promotion-bprime:301:201:capture"),
                new PromotionWalletEffect(43L, 80L, "RELEASE", "promotion-bprime:301:202:release")),
                Map.of(
                        "winners", List.of(Map.of(
                                "campaignId", 201L,
                                "bidderUserId", 42L,
                                "postId", 1001L,
                                "slotIndex", 0,
                                "clearingPrice", 100L)),
                        "walletEffects", List.of(
                                Map.of("ownerUserId", 42L, "amount", 100L, "effectType", "CAPTURE",
                                        "businessRef", "promotion-bprime:301:201:capture"),
                                Map.of("ownerUserId", 43L, "amount", 80L, "effectType", "RELEASE",
                                        "businessRef", "promotion-bprime:301:202:release")),
                        "allocationStartAt", "2026-06-20T11:00:00Z",
                        "allocationEndAt", "2026-06-20T12:00:00Z",
                        "finalWindowStatus", "SETTLED"),
                Instant.parse("2026-06-20T11:00:00Z"));
    }

}
