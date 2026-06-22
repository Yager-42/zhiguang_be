package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionCommandProcessingServiceTest {

    private PromotionRedisDecisionAdapter redisDecisionAdapter;
    private PromotionDecisionLogPort decisionLogPort;
    private PromotionAuctionCommandMapper commandMapper;
    private PromotionAuctionWindowMapper windowMapper;
    private WalletService walletService;
    private PromotionCommandProcessingService service;

    @BeforeEach
    void setUp() {
        redisDecisionAdapter = mock(PromotionRedisDecisionAdapter.class);
        decisionLogPort = mock(PromotionDecisionLogPort.class);
        commandMapper = mock(PromotionAuctionCommandMapper.class);
        windowMapper = mock(PromotionAuctionWindowMapper.class);
        walletService = mock(WalletService.class);
        service = new PromotionCommandProcessingService(redisDecisionAdapter, decisionLogPort,
                commandMapper, windowMapper, walletService);
        when(windowMapper.findById(301L)).thenReturn(window());
    }

    @Test
    void acceptedDecisionHoldsBeforeAppendingKafka() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = acceptedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);

        service.process(command);

        verify(walletService).hold(42L, 120L, WalletLedgerReason.PROMOTION_BPRIME_HOLD,
                WalletBusinessType.PROMOTION, "promotion-bprime:cmd-1:hold");
        verify(decisionLogPort).append(decision);
        verify(commandMapper).updateStatus("cmd-1", "DECIDED");
    }

    @Test
    void acceptedRebidOnlyHoldsIncrementAboveExistingBid() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(),
                List.of(new PromotionWalletEffect(42L, 30L, "HOLD", "promotion-bprime:cmd-1:hold")),
                Instant.parse("2026-06-20T10:05:00Z"));
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);

        service.process(command);

        verify(walletService).hold(42L, 30L, WalletLedgerReason.PROMOTION_BPRIME_HOLD,
                WalletBusinessType.PROMOTION, "promotion-bprime:cmd-1:hold");
        verify(decisionLogPort).append(decision);
    }

    @Test
    void rejectedDecisionDoesNotHold() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = rejectedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);

        service.process(command);

        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), any());
        verify(decisionLogPort).append(decision);
    }

    @Test
    void kafkaAppendFailureAfterHoldReleasesHoldAndMarksLogFailed() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = acceptedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down")).when(decisionLogPort).append(decision);

        assertThatThrownBy(() -> service.process(command)).isInstanceOf(IllegalStateException.class);

        verify(walletService).releaseHold(42L, 120L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, "promotion-bprime:cmd-1:hold-release-after-log-fail");
        verify(commandMapper).updateStatus("cmd-1", "LOG_FAILED");
    }

    @Test
    void redisCommitFailureAfterKafkaAppendDoesNotReleaseHoldOrMarkLogFailed() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = acceptedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(redisDecisionAdapter).commit(decision);

        assertThatThrownBy(() -> service.process(command)).isInstanceOf(IllegalStateException.class);

        verify(decisionLogPort).append(decision);
        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), any());
        verify(commandMapper, never()).updateStatus("cmd-1", "LOG_FAILED");
    }

    @Test
    void kafkaAppendFailureDoesNotCommitRedisHotState() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = acceptedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down")).when(decisionLogPort).append(decision);

        assertThatThrownBy(() -> service.process(command)).isInstanceOf(IllegalStateException.class);

        verify(redisDecisionAdapter, never()).commit(any());
    }

    @Test
    void successfulAcceptedDecisionCommitsRedisHotStateAfterKafkaAppend() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = acceptedDecision();
        when(redisDecisionAdapter.decide(eq(command), eq(100L), eq("OPEN"), any())).thenReturn(decision);

        service.process(command);

        verify(decisionLogPort).append(decision);
        verify(redisDecisionAdapter).commit(decision);
    }

    @Test
    void logFailedCommandIsTerminalAndDoesNotReplayReleasedHoldDecision() {
        PromotionAuctionCommand command = command();
        when(commandMapper.findByCommandId("cmd-1")).thenReturn(PromotionAuctionCommandRecord.builder()
                .commandId("cmd-1")
                .status("LOG_FAILED")
                .build());

        service.process(command);

        verify(redisDecisionAdapter, never()).decide(any(), anyLong(), any(), any());
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), any());
        verify(decisionLogPort, never()).append(any());
    }

    private PromotionAuctionCommand command() {
        return new PromotionAuctionCommand("cmd-1", "idem-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", 120L, "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }

    private PromotionAuctionDecision acceptedDecision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(),
                List.of(new PromotionWalletEffect(42L, 120L, "HOLD", "promotion-bprime:cmd-1:hold")),
                Instant.parse("2026-06-20T10:05:00Z"));
    }

    private PromotionAuctionDecision rejectedDecision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_REJECTED", false, "BELOW_RESERVE", 120L, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }

    private PromotionAuctionWindow window() {
        return PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .reservePrice(100L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
    }
}
