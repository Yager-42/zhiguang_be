package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchItemResult;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchOutcome;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchResult;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandBatch;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.redis.PromotionAuctionUnavailableException;
import com.tongji.promotion.bprime.redis.PromotionBidAdmissionState;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCommandSubmissionServiceTest {

    @Mock
    private PromotionBidRouteRepository routeRepository;
    @Mock
    private PromotionRedisDecisionAdapter decisionAdapter;
    @Mock
    private PromotionPerformanceMetrics performanceMetrics;
    private PromotionBidAdmissionState admissionState;
    private PromotionCommandSubmissionService service;
    @BeforeEach
    void setUp() {
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        admissionState = new PromotionBidAdmissionState(properties);
        service = new PromotionCommandSubmissionService(
                routeRepository, decisionAdapter, performanceMetrics, properties, Runnable::run, admissionState);
    }

    @Test
    void returnsFinalAcceptedDecisionDirectly() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(true, null)));

        SubmitPromotionBidCommandResponse response =
                service.submitAsync(42L, 201L, 120L, "idem-1", now).join();

        ArgumentCaptor<PromotionAuctionCommandBatch> batch = ArgumentCaptor.forClass(PromotionAuctionCommandBatch.class);
        verify(decisionAdapter).decide(batch.capture());
        assertThat(batch.getValue().auctionWindowId()).isEqualTo(301L);
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.resultAvailable()).isTrue();
        assertThat(response.decisionVersion()).isEqualTo(1L);
        assertThat(response.decidedAt()).isEqualTo(now);
    }

    @Test
    void returnsFinalRejectedDecisionDirectly() {
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(false, "BID_NOT_HIGHER")));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 99L, "idem-low", Instant.now()).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
    }

    @Test
    void redisFailureReturnsRetryableUnavailable() {
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any()))
                .thenThrow(new PromotionAuctionUnavailableException("redis down"));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.now()).join();

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.resultAvailable()).isFalse();
        assertThat(response.rejectionReason()).isEqualTo(ErrorCode.PROMOTION_AUCTION_PAUSED.getCode());
    }

    @Test
    void missingEscrowRouteRemainsBusinessRejection() {
        assertThatThrownBy(() -> service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.now()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BusinessException.class);
    }

    @Test
    void legacyPriceCacheDoesNotIssueUnsafeLocalRejection() {
        ;
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(false, "BID_NOT_HIGHER")));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.parse("2026-06-20T10:05:00Z")).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        verify(decisionAdapter).decide(any());
    }

    @Test
    void fastRejectSkipsIdempotentRetryToLuaReplay() {
        ;
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(true, null)));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.parse("2026-06-20T10:05:00Z")).join();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(decisionAdapter).decide(any());
    }

    @Test
    void fastRejectSkipsBidInsideEndMargin() {
        ;
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(true, null)));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.parse("2026-06-20T10:59:59Z")).join();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(decisionAdapter).decide(any());
    }

    @Test
    void fastRejectSkipsClosedWindow() {
        ;
        PromotionBidRoute closedRoute = new PromotionBidRoute(201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L,
                "CLOSED", Instant.parse("2026-06-20T11:00:00Z"), 1, "REDIS_STREAM");
        when(routeRepository.find(201L)).thenReturn(closedRoute);
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(true, null)));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.parse("2026-06-20T10:05:00Z")).join();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(decisionAdapter).decide(any());
    }

    @Test
    void fastRejectSkipsOnRedisError() {
        ;
        when(routeRepository.find(201L)).thenReturn(route());
        when(decisionAdapter.decide(any())).thenReturn(batch(decision(true, null)));

        SubmitPromotionBidCommandResponse response = service
                .submitAsync(42L, 201L, 120L, "idem-1", Instant.parse("2026-06-20T10:05:00Z")).join();

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(decisionAdapter).decide(any());
    }

    private PromotionAuctionBatchResult batch(PromotionAuctionDecision decision) {
        PromotionAuctionBatchOutcome outcome = decision.accepted()
                ? PromotionAuctionBatchOutcome.ACCEPTED : PromotionAuctionBatchOutcome.BID_NOT_HIGHER;
        return new PromotionAuctionBatchResult(
                List.of(new PromotionAuctionBatchItemResult(0, outcome, decision)),
                decision.bidAmount(), decision.commandId(), decision.campaignId(), "OPEN",
                Instant.parse("2026-06-20T11:00:00Z").toEpochMilli(), decision.decisionVersion());
    }

    private PromotionBidRoute route() {
        return new PromotionBidRoute(201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L,
                "OPEN", Instant.parse("2026-06-20T11:00:00Z"), 1, "REDIS_STREAM");
    }

    private PromotionAuctionDecision decision(boolean accepted, String reason) {
        return new PromotionAuctionDecision(
                "d-1", "cmd-1", "hash", 301L, 1L, 0L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", accepted ? "BID_ACCEPTED" : "BID_REJECTED", accepted, reason,
                120L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));
    }
}
