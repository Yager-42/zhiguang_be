package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.bprime.redis.PromotionBidFastPathPrecheckRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCommandSubmissionServiceTest {

    @Mock
    private PromotionBidRouteRepository routeRepository;
    @Mock
    private PromotionCommandMessagePort messagePort;
    @Mock
    private PromotionPerformanceMetrics performanceMetrics;
    @Mock
    private PromotionBidFastPathPrecheckBatcher fastPathPrecheckBatcher;

    private PromotionCommandSubmissionService service;
    private PromotionBidFastRejectFilter fastRejectFilter;

    @BeforeEach
    void setUp() {
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        fastRejectFilter = new PromotionBidFastRejectFilter(properties);
        service = new PromotionCommandSubmissionService(routeRepository, messagePort, performanceMetrics, properties,
                fastRejectFilter, fastPathPrecheckBatcher, Runnable::run);
    }

    @Test
    void submitReadsRedisRouteAndWaitsForBrokerSendWithoutMysqlCommand() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(routeRepository.find(201L)).thenReturn(route());

        SubmitPromotionBidCommandResponse response = service.submitAsync(42L, 201L, 120L, "idem-1", now).join();

        ArgumentCaptor<PromotionAuctionCommand> captor = ArgumentCaptor.forClass(PromotionAuctionCommand.class);
        verify(messagePort).send(captor.capture());
        verify(performanceMetrics).recordIngressAccepted();
        assertThat(captor.getValue().type()).isEqualTo("BID");
        assertThat(captor.getValue().auctionWindowId()).isEqualTo(301L);
        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(response.commandId()).startsWith("promotion-bprime-");
    }

    @Test
    void sameIdempotencyProducesSameCommandIdentity() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(routeRepository.find(201L)).thenReturn(route());

        String first = service.submitAsync(42L, 201L, 120L, "idem-1", now).join().commandId();
        String second = service.submitAsync(42L, 201L, 120L, "idem-1", now.plusMillis(1)).join().commandId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void missingEscrowRouteRejectsBeforeBrokerSend() {
        assertThatThrownBy(() -> service.submitAsync(42L, 201L, 120L, "idem-1", Instant.now()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error.getCause()).getErrorCode())
                        .isEqualTo(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED));
        verify(messagePort, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void belowReserveDefersToAuthoritativeCommandPath() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(routeRepository.find(201L)).thenReturn(route());

        SubmitPromotionBidCommandResponse response = service.submitAsync(42L, 201L, 99L, "idem-low", now).join();

        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(messagePort).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptedWatermarkRejectsBeforeRedisRouteAndBroker() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        fastRejectFilter.observeRoute(route());
        fastRejectFilter.observeDecision(acceptedDecision(150L));
        when(fastPathPrecheckBatcher.checkAsync(301L, serviceCommandId("idem-stale")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PromotionBidFastPathPrecheckRepository.Result(true, true, false)));

        SubmitPromotionBidCommandResponse response = service.submitAsync(
                42L, 201L, 150L, "idem-stale", now).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        verify(routeRepository, never()).find(org.mockito.ArgumentMatchers.anyLong());
        verify(messagePort, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateCandidateFallsThroughSoLuaCanReplayOriginalDecision() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        fastRejectFilter.observeRoute(route());
        fastRejectFilter.observeDecision(acceptedDecision(150L));
        when(fastPathPrecheckBatcher.checkAsync(301L, serviceCommandId("idem-replay")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PromotionBidFastPathPrecheckRepository.Result(true, true, true)));
        when(routeRepository.find(201L)).thenReturn(route());

        SubmitPromotionBidCommandResponse response = service.submitAsync(
                42L, 201L, 150L, "idem-replay", now).join();

        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(messagePort).send(org.mockito.ArgumentMatchers.any());
        verify(performanceMetrics, never()).recordFastRejected(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unavailablePrecheckFallsThroughAndIsMetered() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        fastRejectFilter.observeRoute(route());
        fastRejectFilter.observeDecision(acceptedDecision(150L));
        when(fastPathPrecheckBatcher.checkAsync(301L, serviceCommandId("idem-uncertain")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PromotionBidFastPathPrecheckRepository.Result(false, false, false)));
        when(routeRepository.find(201L)).thenReturn(route());

        SubmitPromotionBidCommandResponse response = service.submitAsync(
                42L, 201L, 150L, "idem-uncertain", now).join();

        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(performanceMetrics).recordFastRejectPrecheckFailure();
        verify(messagePort).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fastRejectUsesBatchedPrecheck() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        fastRejectFilter.observeRoute(route());
        fastRejectFilter.observeDecision(acceptedDecision(150L));
        String commandId = serviceCommandId("idem-batched");
        when(fastPathPrecheckBatcher.checkAsync(301L, commandId))
                .thenReturn(CompletableFuture.completedFuture(
                        new PromotionBidFastPathPrecheckRepository.Result(true, true, false)));

        SubmitPromotionBidCommandResponse response = service.submitAsync(
                42L, 201L, 150L, "idem-batched", now).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        verify(routeRepository, never()).find(org.mockito.ArgumentMatchers.anyLong());
        verify(messagePort, never()).send(org.mockito.ArgumentMatchers.any());
    }

    private PromotionBidRoute route() {
        return new PromotionBidRoute(201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L,
                "OPEN", Instant.parse("2026-06-20T11:00:00Z"));
    }

    private PromotionAuctionDecision acceptedDecision(long bidAmount) {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, bidAmount, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }

    private String serviceCommandId(String idempotencyKey) {
        return com.tongji.promotion.bprime.model.PromotionCommandIdentity.bidCommandId(301L, 42L, idempotencyKey);
    }
}
