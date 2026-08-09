package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionCommandIdentity;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.bprime.redis.PromotionBidFastPathPrecheckRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** BD 风格热收单：Redis 路由预检后直接可靠投递 RocketMQ，不访问 MySQL。 */
@Service
public class PromotionCommandSubmissionService {

    private final PromotionBidRouteRepository routeRepository;
    private final PromotionCommandMessagePort messagePort;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final PromotionBPrimeProperties properties;
    private final PromotionBidFastRejectFilter fastRejectFilter;
    private final PromotionBidFastPathPrecheckBatcher fastPathPrecheckBatcher;
    private final TaskExecutor submissionExecutor;

    public PromotionCommandSubmissionService(PromotionBidRouteRepository routeRepository,
                                             PromotionCommandMessagePort messagePort,
                                             PromotionPerformanceMetrics performanceMetrics,
                                             PromotionBPrimeProperties properties,
                                             PromotionBidFastRejectFilter fastRejectFilter,
                                             PromotionBidFastPathPrecheckBatcher fastPathPrecheckBatcher,
                                             @Qualifier("promotionBidSubmissionExecutor")
                                             TaskExecutor submissionExecutor) {
        this.routeRepository = routeRepository;
        this.messagePort = messagePort;
        this.performanceMetrics = performanceMetrics;
        this.properties = properties;
        this.fastRejectFilter = fastRejectFilter;
        this.fastPathPrecheckBatcher = fastPathPrecheckBatcher;
        this.submissionExecutor = submissionExecutor;
    }

    /**
     * 异步提交 WebSocket 竞价；只有少量权威链路回退会占用有界提交线程池。
     *
     * @param userId 当前认证用户 ID
     * @param campaignId 推广活动 ID
     * @param bidAmount 出价金额，必须为正数
     * @param idempotencyKey 客户端幂等键，不允许为空白
     * @param now 服务端接收时间；为空时使用当前时间
     * @return 竞价入口 ACK，异常通过返回的 future 传播
     */
    public CompletableFuture<SubmitPromotionBidCommandResponse> submitAsync(
            long userId,
            long campaignId,
            long bidAmount,
            String idempotencyKey,
            Instant now) {
        final SubmissionContext context;
        try {
            context = prepare(userId, campaignId, bidAmount, idempotencyKey, now);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        Optional<CandidateContext> candidate = findCandidate(context);
        if (candidate.isEmpty()) {
            return submitAuthoritativeAsync(context);
        }
        CandidateContext candidateContext = candidate.get();
        return fastPathPrecheckBatcher.checkAsync(candidateContext.candidate().auctionWindowId(),
                        candidateContext.commandId())
                .thenCompose(precheck -> tryFastReject(candidateContext, precheck)
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> submitAuthoritativeAsync(context)));
    }

    private SubmissionContext prepare(long userId, long campaignId, long bidAmount,
                                      String idempotencyKey, Instant now) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED,
                    "promotion bprime is disabled");
        }
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bidAmount must be greater than 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey must not be blank");
        }
        Instant submittedAt = now == null ? Instant.now() : now;
        String normalizedIdempotencyKey = idempotencyKey.trim();
        return new SubmissionContext(userId, campaignId, bidAmount, normalizedIdempotencyKey, submittedAt);
    }

    private Optional<CandidateContext> findCandidate(SubmissionContext context) {
        return fastRejectFilter.findCandidate(context.userId(), context.campaignId(), context.bidAmount(),
                        context.submittedAt())
                .map(candidate -> new CandidateContext(candidate, PromotionCommandIdentity.bidCommandId(
                        candidate.auctionWindowId(), context.userId(), context.idempotencyKey())));
    }

    private Optional<SubmitPromotionBidCommandResponse> tryFastReject(
            CandidateContext candidate,
            PromotionBidFastPathPrecheckRepository.Result precheck) {
        if (precheck.allowsFastReject()) {
            return Optional.of(rejected(candidate.commandId(), candidate.candidate()));
        }
        if (!precheck.available()) {
            performanceMetrics.recordFastRejectPrecheckFailure();
        }
        return Optional.empty();
    }

    private CompletableFuture<SubmitPromotionBidCommandResponse> submitAuthoritativeAsync(SubmissionContext context) {
        CompletableFuture<SubmitPromotionBidCommandResponse> result = new CompletableFuture<>();
        try {
            submissionExecutor.execute(() -> {
                try {
                    result.complete(submitAuthoritative(context));
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private SubmitPromotionBidCommandResponse submitAuthoritative(SubmissionContext context) {
        PromotionBidRoute route = routeRepository.find(context.campaignId());
        if (route == null || route.bidderUserId() != context.userId()) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED);
        }
        if (!"OPEN".equals(route.windowStatus()) || !context.submittedAt().isBefore(route.windowEndAt())) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        fastRejectFilter.observeRoute(route);

        String commandId = PromotionCommandIdentity.bidCommandId(
                route.auctionWindowId(), context.userId(), context.idempotencyKey());
        String requestHash = requestHash(context.campaignId(), context.userId(), route.auctionWindowId(),
                context.bidAmount(), context.idempotencyKey());
        PromotionAuctionCommand command = new PromotionAuctionCommand(
                commandId,
                context.idempotencyKey(),
                requestHash,
                route.auctionWindowId(),
                context.campaignId(),
                context.userId(),
                route.postId(),
                route.resourceType(),
                context.bidAmount(),
                route.reservePrice(),
                route.windowStatus(),
                "BID",
                context.submittedAt());
        messagePort.send(command);
        performanceMetrics.recordIngressAccepted();
        return new SubmitPromotionBidCommandResponse(commandId, String.valueOf(route.auctionWindowId()),
                "PUBLISHED", false, null);
    }

    private SubmitPromotionBidCommandResponse rejected(
            String commandId,
            PromotionBidFastRejectFilter.FastRejectCandidate rejection) {
        String reason = rejection.reason().name();
        performanceMetrics.recordFastRejected(reason);
        return new SubmitPromotionBidCommandResponse(
                commandId,
                String.valueOf(rejection.auctionWindowId()), "REJECTED", true, reason);
    }

    String requestHash(long campaignId, long userId, long auctionWindowId, long bidAmount, String idempotencyKey) {
        return PromotionCommandIdentity.requestHash(campaignId, userId, auctionWindowId, bidAmount, idempotencyKey);
    }

    private record SubmissionContext(long userId, long campaignId, long bidAmount,
                                     String idempotencyKey, Instant submittedAt) {
    }

    private record CandidateContext(PromotionBidFastRejectFilter.FastRejectCandidate candidate, String commandId) {
    }
}
