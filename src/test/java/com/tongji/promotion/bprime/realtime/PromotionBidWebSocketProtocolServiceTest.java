package com.tongji.promotion.bprime.realtime;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.service.PromotionCommandSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionBidWebSocketProtocolServiceTest {

    private PromotionCommandSubmissionService submissionService;
    private PromotionPerformanceMetrics performanceMetrics;
    private PromotionBidWebSocketProtocolService protocolService;

    @BeforeEach
    void setUp() {
        submissionService = mock(PromotionCommandSubmissionService.class);
        performanceMetrics = mock(PromotionPerformanceMetrics.class);
        protocolService = new PromotionBidWebSocketProtocolService(submissionService, performanceMetrics);
    }

    @Test
    void authenticatedBidUsesSharedSubmissionService() {
        PromotionWebSocketBidRequest request = new PromotionWebSocketBidRequest("201", 120L, "idem-1");
        when(submissionService.submitAsync(eq(42L), eq(201L), eq(120L), eq("idem-1"), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new SubmitPromotionBidCommandResponse("cmd-1", "301", "PUBLISHED", false)));

        PromotionWebSocketBidAck response = protocolService.submit(request, principal("42")).join();

        assertThat(response.commandId()).isEqualTo("cmd-1");
        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(performanceMetrics).recordWebSocketBidAck("PUBLISHED");
    }

    @Test
    void guestBidIsRejectedWithoutEnteringSubmissionService() {
        PromotionWebSocketBidAck response = protocolService.submit(
                new PromotionWebSocketBidRequest("201", 120L, "idem-1"), principal("guest")).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("UNAUTHORIZED");
        verify(submissionService, never()).submitAsync(anyLong(), anyLong(), anyLong(),
                any(String.class), any(Instant.class));
    }

    @Test
    void businessRejectionIsReturnedAsAckInsteadOfClosingConnection() {
        PromotionWebSocketBidRequest request = new PromotionWebSocketBidRequest("201", 120L, "idem-1");
        when(submissionService.submitAsync(eq(42L), eq(201L), eq(120L), eq("idem-1"), any(Instant.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new BusinessException(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED)));

        PromotionWebSocketBidAck response = protocolService.submit(request, principal("42")).join();

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectionReason()).isEqualTo("PROMOTION_BID_ESCROW_REQUIRED");
        verify(performanceMetrics).recordWebSocketBidAck("REJECTED");
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
