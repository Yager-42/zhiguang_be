package com.tongji.promotion.bprime.service;

import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionCommandIdentity;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** MySQL 预授权完成后，通过同窗口顺序命令把授权投影到 Redis 热状态。 */
@Service
public class PromotionBidEscrowService {

    private final PromotionBidEscrowTransactionService transactionService;
    private final PromotionCommandMessagePort commandMessagePort;
    private final PromotionBidRouteRepository routeRepository;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionAuctionHotStateRepository hotStateRepository;
    private final PromotionBidFastRejectFilter fastRejectFilter;

    public PromotionBidEscrowService(PromotionBidEscrowTransactionService transactionService,
                                     PromotionCommandMessagePort commandMessagePort,
                                     PromotionBidRouteRepository routeRepository,
                                     PromotionProjectionCheckpointMapper checkpointMapper,
                                     PromotionAuctionHotStateRepository hotStateRepository,
                                     PromotionBidFastRejectFilter fastRejectFilter) {
        this.transactionService = transactionService;
        this.commandMessagePort = commandMessagePort;
        this.routeRepository = routeRepository;
        this.checkpointMapper = checkpointMapper;
        this.hotStateRepository = hotStateRepository;
        this.fastRejectFilter = fastRejectFilter;
    }

    public PromotionBidEscrowAuthorizationResponse authorize(long userId, long campaignId, long amount, Instant now) {
        PromotionBidEscrowTransactionService.Authorization authorization =
                transactionService.authorize(userId, campaignId, amount, now);
        PromotionBidEscrowRecord escrow = authorization.escrow();
        PromotionBidRoute route = new PromotionBidRoute(
                campaignId,
                userId,
                authorization.campaign().getPostId(),
                escrow.getAuctionWindowId(),
                authorization.campaign().getResourceType().name(),
                authorization.window().getReservePrice(),
                escrow.getAuthorizedAmount(),
                authorization.window().getStatus().name(),
                authorization.window().getWindowEndAt());
        PromotionProjectionCheckpointRecord checkpoint =
                checkpointMapper.findByAuctionWindowId(escrow.getAuctionWindowId());
        hotStateRepository.initialize(route, checkpoint == null ? 0L : checkpoint.getLastDecisionVersion());
        String idempotencyKey = "escrow:" + escrow.getAuthorizedAmount();
        String requestHash = PromotionCommandIdentity.requestHash(campaignId, userId,
                escrow.getAuctionWindowId(), escrow.getAuthorizedAmount(), idempotencyKey);
        PromotionAuctionCommand command = new PromotionAuctionCommand(
                PromotionCommandIdentity.escrowCommandId(escrow.getAuctionWindowId(), campaignId,
                        escrow.getAuthorizedAmount()),
                idempotencyKey,
                requestHash,
                escrow.getAuctionWindowId(),
                campaignId,
                userId,
                authorization.campaign().getPostId(),
                authorization.campaign().getResourceType().name(),
                escrow.getAuthorizedAmount(),
                authorization.window().getReservePrice(),
                authorization.window().getStatus().name(),
                "ESCROW_NOTIFY",
                now);
        commandMessagePort.send(command);
        routeRepository.save(route, now);
        fastRejectFilter.observeRoute(route);
        return new PromotionBidEscrowAuthorizationResponse(
                String.valueOf(escrow.getAuctionWindowId()),
                String.valueOf(campaignId),
                escrow.getAuthorizedAmount(),
                escrow.getCurrentHold(),
                escrow.getStatus());
    }
}
