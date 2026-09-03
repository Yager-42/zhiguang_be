package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionCommandIdentity;
import com.tongji.promotion.bprime.redis.PromotionBidAdmissionState;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket 竞价入口：路由后进入每窗口 flat combiner，并同步返回 Redis Lua 最终裁决。
 */
@Service
public class PromotionCommandSubmissionService {

    /** 2^53-1：Lua/Redis 金额上限（Go MAX_MONEY 同构）。 */
    private static final long MAX_MONEY = 9007199254740991L;

    private final PromotionBidRouteRepository routeRepository;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final PromotionBPrimeProperties properties;
    private final PromotionAuctionAvailabilityGate availabilityGate;
    private final PromotionBidAdmissionState admissionState;
    private final PromotionWindowBidCombiner combiner;

    public PromotionCommandSubmissionService(PromotionBidRouteRepository routeRepository,
                                             PromotionRedisDecisionAdapter decisionAdapter,
                                             PromotionPerformanceMetrics performanceMetrics,
                                             PromotionBPrimeProperties properties,
                                             PromotionAuctionAvailabilityGate availabilityGate,
                                             @Qualifier("promotionBidDrainerExecutor") TaskExecutor drainerExecutor,
                                             PromotionBidAdmissionState admissionState) {
        this.routeRepository = routeRepository;
        this.performanceMetrics = performanceMetrics;
        this.properties = properties;
        this.availabilityGate = availabilityGate;
        this.admissionState = admissionState;
        this.combiner = new PromotionWindowBidCombiner(decisionAdapter, drainerExecutor, properties,
                performanceMetrics, admissionState, this::response, this::unavailable);
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
        try {
            return submitPrepared(context);
        } catch (BusinessException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(unavailable(null, null));
        }
    }

    private SubmissionContext prepare(long userId, long campaignId, long bidAmount,
                                      String idempotencyKey, Instant now) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED);
        }
        availabilityGate.requireAvailable();
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

    private CompletableFuture<SubmitPromotionBidCommandResponse> submitPrepared(SubmissionContext context) {
        PromotionBidRoute route = routeRepository.find(context.campaignId());
        if (route == null || route.bidderUserId() != context.userId()) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED);
        }
        String commandId = PromotionCommandIdentity.bidCommandId(
                route.auctionWindowId(), context.userId(), context.idempotencyKey());
        SubmitPromotionBidCommandResponse fastRejected = fastReject(context, route, commandId);
        if (fastRejected != null) {
            return CompletableFuture.completedFuture(fastRejected);
        }
        return combiner.submit(command(context, route, commandId));
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
     * 仅依据 Redis 已确认的版本化状态做零 Redis 确定性拒绝；当前赢家命令和终场 margin 必须进 Lua。
     */
    private SubmitPromotionBidCommandResponse fastReject(SubmissionContext context, PromotionBidRoute route,
                                                         String commandId) {
        if (!properties.isFastRejectEnabled()) {
            return null;
        }
        PromotionBidAdmissionState.Snapshot state = admissionState.get(route.auctionWindowId());
        if (state == null || !"OPEN".equals(state.status()) || commandId.equals(state.winnerCommandId())) {
            return null;
        }
        Instant safeEndAt = Instant.ofEpochMilli(state.actualEndAtEpochMs())
                .minusSeconds(properties.getFastRejectMarginSeconds());
        if (!context.submittedAt().isBefore(safeEndAt)) {
            return null;
        }
        long increment = route.incrementCents() > 0
                ? route.incrementCents()
                : properties.auctionRules(com.tongji.promotion.model.PromotionResourceType
                        .valueOf(route.resourceType())).incrementCents();
        long required = state.committedPriceCents() > MAX_MONEY - increment
                ? MAX_MONEY : state.committedPriceCents() + increment;
        if (route.capPriceCents() > 0 && required > route.capPriceCents()) {
            required = route.capPriceCents();
        }
        if (context.bidAmount() >= required) {
            return null;
        }
        performanceMetrics.recordFastRejected();
        return new SubmitPromotionBidCommandResponse(
                commandId, String.valueOf(route.auctionWindowId()), "REJECTED", true,
                "BID_NOT_HIGHER", null, state.decisionVersion(), context.bidAmount(), context.submittedAt(), required,
                false, state.winnerCampaignId() == 0 ? null : String.valueOf(state.winnerCampaignId()),
                state.committedPriceCents());
    }

    private SubmitPromotionBidCommandResponse response(PromotionAuctionDecision decision) {
        PromotionAuctionDecision.BidFacts facts = decision.bidFacts();
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
                facts.requiredAmount().orElse(null),
                decision.accepted(),
                facts.winnerCampaignId().orElse(null),
                facts.currentPriceCents().orElse(null));
    }

    private SubmitPromotionBidCommandResponse unavailable(PromotionAuctionCommand command) {
        return unavailable(command.commandId(), new PromotionBidRoute(
                command.campaignId(), command.bidderUserId(), command.postId(), command.auctionWindowId(),
                command.resourceType(), command.reservePrice(), 0L, command.windowStatus(),
                command.submittedAt(), 1));
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
