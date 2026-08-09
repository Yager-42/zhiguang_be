package com.tongji.promotion.bprime.realtime;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.service.PromotionCommandSubmissionService;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Shared authentication, submission, and ACK mapping for STOMP and native WebSocket transports. */
@Service
public class PromotionBidWebSocketProtocolService {

    private static final String GUEST_PRINCIPAL = "guest";

    private final PromotionCommandSubmissionService submissionService;
    private final PromotionPerformanceMetrics performanceMetrics;

    public PromotionBidWebSocketProtocolService(PromotionCommandSubmissionService submissionService,
                                                PromotionPerformanceMetrics performanceMetrics) {
        this.submissionService = submissionService;
        this.performanceMetrics = performanceMetrics;
    }

    public CompletableFuture<PromotionWebSocketBidAck> submit(
            PromotionWebSocketBidRequest request,
            Principal principal) {
        if (request == null) {
            return CompletableFuture.completedFuture(rejected(null, ErrorCode.BAD_REQUEST.getCode()));
        }
        if (principal == null || GUEST_PRINCIPAL.equals(principal.getName())) {
            return CompletableFuture.completedFuture(rejected(request.idempotencyKey(), "UNAUTHORIZED"));
        }
        final long userId;
        final long campaignId;
        try {
            userId = Long.parseLong(principal.getName());
            campaignId = Long.parseLong(request.campaignId());
        } catch (NumberFormatException exception) {
            return CompletableFuture.completedFuture(
                    rejected(request.idempotencyKey(), ErrorCode.BAD_REQUEST.getCode()));
        }
        return submissionService.submitAsync(userId, campaignId, request.bidAmount(), request.idempotencyKey(),
                        Instant.now())
                .handle((response, exception) -> toAck(request.idempotencyKey(), response, exception));
    }

    public PromotionWebSocketBidAck rejected(String idempotencyKey, String reason) {
        performanceMetrics.recordWebSocketBidAck("REJECTED");
        return PromotionWebSocketBidAck.rejected(idempotencyKey, reason);
    }

    private PromotionWebSocketBidAck toAck(
            String idempotencyKey,
            SubmitPromotionBidCommandResponse response,
            Throwable exception) {
        if (exception == null) {
            performanceMetrics.recordWebSocketBidAck(response.status());
            return PromotionWebSocketBidAck.from(idempotencyKey, response);
        }
        Throwable cause = unwrap(exception);
        if (cause instanceof BusinessException businessException) {
            return rejected(idempotencyKey, businessException.getErrorCode().getCode());
        }
        throw new CompletionException(cause);
    }

    private Throwable unwrap(Throwable exception) {
        Throwable cause = exception;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
