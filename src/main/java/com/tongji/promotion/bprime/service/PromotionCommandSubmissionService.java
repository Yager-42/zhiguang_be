package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionCommandIdentity;
import com.tongji.promotion.bprime.redis.PromotionAuctionUnavailableException;
import com.tongji.promotion.bprime.redis.PromotionAuctionRedisKeys;
import com.tongji.promotion.bprime.model.PromotionBidFastRejectionReason;
import com.tongji.promotion.bprime.redis.PromotionBidPriceCache;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket 竞价入口：在有界线程池中同步取得 Redis Lua 的最终裁决。
 */
@Service
public class PromotionCommandSubmissionService {

    /** 2^53-1：Lua/Redis 金额上限（Go MAX_MONEY 同构）。 */
    private static final long MAX_MONEY = 9007199254740991L;

    private final PromotionBidRouteRepository routeRepository;
    private final PromotionRedisDecisionAdapter decisionAdapter;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final PromotionBPrimeProperties properties;
    private final TaskExecutor submissionExecutor;
    private final PromotionBidPriceCache priceCache;
    private final StringRedisTemplate redisTemplate;

    public PromotionCommandSubmissionService(PromotionBidRouteRepository routeRepository,
                                             PromotionRedisDecisionAdapter decisionAdapter,
                                             PromotionPerformanceMetrics performanceMetrics,
                                             PromotionBPrimeProperties properties,
                                             @Qualifier("promotionBidSubmissionExecutor")
                                             TaskExecutor submissionExecutor,
                                             PromotionBidPriceCache priceCache,
                                             StringRedisTemplate redisTemplate) {
        this.routeRepository = routeRepository;
        this.decisionAdapter = decisionAdapter;
        this.performanceMetrics = performanceMetrics;
        this.properties = properties;
        this.submissionExecutor = submissionExecutor;
        this.priceCache = priceCache;
        this.redisTemplate = redisTemplate;
    }

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
        CompletableFuture<SubmitPromotionBidCommandResponse> result = new CompletableFuture<>();
        try {
            submissionExecutor.execute(() -> {
                try {
                    result.complete(submitAuthoritative(context));
                } catch (BusinessException exception) {
                    result.completeExceptionally(exception);
                } catch (RuntimeException exception) {
                    result.complete(unavailable(null, null));
                }
            });
        } catch (RuntimeException exception) {
            result.complete(unavailable(null, null));
        }
        return result;
    }

    private SubmissionContext prepare(long userId, long campaignId, long bidAmount,
                                      String idempotencyKey, Instant now) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED);
        }
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bidAmount must be greater than 0");
        }
        if (bidAmount > MAX_MONEY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bidAmount must not exceed 2^53-1");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey must not be blank");
        }
        return new SubmissionContext(userId, campaignId, bidAmount, idempotencyKey.trim(),
                now == null ? Instant.now() : now);
    }

    private SubmitPromotionBidCommandResponse submitAuthoritative(SubmissionContext context) {
        PromotionBidRoute route = null;
        String commandId = null;
        try {
            route = routeRepository.find(context.campaignId());
            if (route == null || route.bidderUserId() != context.userId()) {
                throw new BusinessException(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED);
            }
            commandId = PromotionCommandIdentity.bidCommandId(
                    route.auctionWindowId(), context.userId(), context.idempotencyKey());
            if (!"REDIS_STREAM".equals(route.decisionPath())) {
                return unavailable(commandId, route);
            }
            SubmitPromotionBidCommandResponse fastRejected = fastReject(context, route, commandId);
            if (fastRejected != null) {
                return fastRejected;
            }
            PromotionAuctionCommand command = command(context, route, commandId);
            PromotionAuctionDecision decision = decisionAdapter.decide(command);
            performanceMetrics.recordIngressAccepted();
            performanceMetrics.recordDecisionDurable(decision);
            return response(decision);
        } catch (PromotionAuctionUnavailableException exception) {
            return unavailable(commandId, route);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return unavailable(commandId, route);
        }
    }

    private PromotionAuctionCommand command(SubmissionContext context, PromotionBidRoute route, String commandId) {
        String requestHash = requestHash(context.campaignId(), context.userId(), route.auctionWindowId(),
                context.bidAmount(), context.idempotencyKey());
        return new PromotionAuctionCommand(
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
    }

    /**
     * 网关侧价格预拒：出价不高于本进程已见的窗口共享当前价时本地返回 BID_NOT_HIGHER，
     * 不进入 Lua 裁决。任何不确定（缓存缺失、margin 内、终态、幂等重试、Redis 异常）都放行 Lua。
     * 本地预拒不带 requiredAmount（Go fast-reject 同构；只有 Lua 拒绝路径带）。
     */
    private SubmitPromotionBidCommandResponse fastReject(SubmissionContext context, PromotionBidRoute route,
                                                         String commandId) {
        if (!properties.isFastRejectEnabled() || !"OPEN".equals(route.windowStatus())) {
            return null;
        }
        if (!context.submittedAt().isBefore(
                route.windowEndAt().minusSeconds(properties.getFastRejectMarginSeconds()))) {
            return null;
        }
        Long cachedPrice = priceCache.get(route.auctionWindowId());
        if (cachedPrice == null || context.bidAmount() > cachedPrice) {
            return null;
        }
        // 幂等优先：该 commandId 已裁决过（重试）→ 放行 Lua 重放原裁决
        try {
            if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(
                    PromotionAuctionRedisKeys.commandBucket(route.auctionWindowId()), commandId))) {
                return null;
            }
        } catch (RuntimeException exception) {
            return null; // Redis 异常 → 放行 Lua（保守）
        }
        performanceMetrics.recordFastRejected();
        return new SubmitPromotionBidCommandResponse(
                commandId,
                String.valueOf(route.auctionWindowId()),
                "REJECTED",
                true,
                PromotionBidFastRejectionReason.BID_NOT_HIGHER.name(),
                null,
                null,
                null,
                null,
                null);
    }

    private SubmitPromotionBidCommandResponse response(PromotionAuctionDecision decision) {
        return new SubmitPromotionBidCommandResponse(
                decision.commandId(),
                String.valueOf(decision.auctionWindowId()),
                decision.accepted() ? "ACCEPTED" : "REJECTED",
                true,
                decision.rejectionReason(),
                decision.decisionId(),
                decision.decisionVersion(),
                decision.bidAmount(),
                decision.decidedAt(),
                requiredAmount(decision));
    }

    private Long requiredAmount(PromotionAuctionDecision decision) {
        Object value = decision.payload().get("requiredAmount");
        return value instanceof Number number ? number.longValue() : null;
    }

    private SubmitPromotionBidCommandResponse unavailable(String commandId, PromotionBidRoute route) {
        return new SubmitPromotionBidCommandResponse(
                commandId,
                route == null ? null : String.valueOf(route.auctionWindowId()),
                "UNAVAILABLE",
                false,
                ErrorCode.PROMOTION_AUCTION_PAUSED.getCode(),
                null,
                null,
                null,
                null,
                null);
    }

    String requestHash(long campaignId, long userId, long auctionWindowId,
                       long bidAmount, String idempotencyKey) {
        return PromotionCommandIdentity.requestHash(
                campaignId, userId, auctionWindowId, bidAmount, idempotencyKey);
    }

    private record SubmissionContext(long userId, long campaignId, long bidAmount,
                                     String idempotencyKey, Instant submittedAt) {
    }
}
