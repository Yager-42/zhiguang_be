package com.tongji.promotion.bprime.service;

import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** MySQL 预授权完成后，通过同窗口顺序命令把授权投影到 Redis 热状态。 */
@Service
public class PromotionBidEscrowService {

    private final PromotionBidEscrowTransactionService transactionService;
    private final PromotionBidRouteRepository routeRepository;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionAuctionHotStateRepository hotStateRepository;
    private final ReconciliationTaskMapper reconciliationTaskMapper;
    private final PromotionBPrimeProperties properties;

    public PromotionBidEscrowService(PromotionBidEscrowTransactionService transactionService,
                                     PromotionBidRouteRepository routeRepository,
                                     PromotionProjectionCheckpointMapper checkpointMapper,
                                     PromotionAuctionHotStateRepository hotStateRepository,
                                     ReconciliationTaskMapper reconciliationTaskMapper,
                                     PromotionBPrimeProperties properties) {
        this.transactionService = transactionService;
        this.routeRepository = routeRepository;
        this.checkpointMapper = checkpointMapper;
        this.hotStateRepository = hotStateRepository;
        this.reconciliationTaskMapper = reconciliationTaskMapper;
        this.properties = properties;
    }

    public PromotionBidEscrowAuthorizationResponse authorize(long userId, long campaignId, long amount, Instant now) {
        PromotionBidEscrowTransactionService.Authorization authorization =
                transactionService.authorize(userId, campaignId, amount, now);
        PromotionBidEscrowRecord escrow = authorization.escrow();
        PromotionBPrimeProperties.AuctionRules rules = properties.auctionRules(
                authorization.campaign().getResourceType()).withBoundAntiSnipe();
        PromotionBidRoute route = new PromotionBidRoute(
                campaignId,
                userId,
                authorization.campaign().getPostId(),
                escrow.getAuctionWindowId(),
                authorization.campaign().getResourceType().name(),
                authorization.window().getReservePrice(),
                escrow.getAuthorizedAmount(),
                authorization.window().getStatus().name(),
                authorization.window().getWindowEndAt(),
                authorization.window().getSlotCount(),
                rules.incrementCents(),
                rules.capPriceCents(),
                rules.extendWindowSec(),
                rules.extendSec(),
                rules.maxExtensions());
        try {
            PromotionProjectionCheckpointRecord checkpoint =
                    checkpointMapper.findByAuctionWindowId(escrow.getAuctionWindowId());
            hotStateRepository.initialize(route, checkpoint == null ? 0L : checkpoint.getLastDecisionVersion());
            hotStateRepository.projectAuthorization(route);
            routeRepository.save(route, now);
            if (authorization.projectionTask() != null) {
                reconciliationTaskMapper.markSucceeded(authorization.projectionTask().getId(), 0L);
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED,
                    "escrow authorized; Redis projection pending retry");
        }
        return new PromotionBidEscrowAuthorizationResponse(
                String.valueOf(escrow.getAuctionWindowId()),
                String.valueOf(campaignId),
                escrow.getAuthorizedAmount(),
                escrow.getCurrentHold(),
                escrow.getStatus());
    }
}
