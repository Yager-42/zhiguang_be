package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Owns the ordered lifecycle that makes MySQL auction facts usable as Redis hot state.
 */
@Service
public class PromotionAuctionHotStateLifecycle {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionAuctionHotStateRepository hotStateRepository;
    private final PromotionBidRouteRepository routeRepository;
    private final ReconciliationTaskMapper reconciliationTaskMapper;
    private final PromotionBPrimeProperties properties;

    public PromotionAuctionHotStateLifecycle(PromotionAuctionWindowMapper windowMapper,
                                             PromotionProjectionCheckpointMapper checkpointMapper,
                                             PromotionAuctionHotStateRepository hotStateRepository,
                                             PromotionBidRouteRepository routeRepository,
                                             ReconciliationTaskMapper reconciliationTaskMapper,
                                             PromotionBPrimeProperties properties) {
        this.windowMapper = windowMapper;
        this.checkpointMapper = checkpointMapper;
        this.hotStateRepository = hotStateRepository;
        this.routeRepository = routeRepository;
        this.reconciliationTaskMapper = reconciliationTaskMapper;
        this.properties = properties;
    }

    public void activateWindow(PromotionAuctionWindow window) {
        hotStateRepository.initialize(window, 0L);
    }

    public void makeAuthorizationReady(PromotionBidEscrowTransactionService.Authorization authorization,
                                       Instant now) {
        projectAuthorization(authorization.campaign(), authorization.window(), authorization.escrow(), now);
        if (authorization.projectionTask() != null) {
            reconciliationTaskMapper.markSucceeded(authorization.projectionTask().getId(), 0L);
        }
    }

    public void restoreAuthorization(PromotionCampaign campaign,
                                     PromotionAuctionWindow window,
                                     PromotionBidEscrowRecord escrow,
                                     Instant now) {
        projectAuthorization(campaign, window, escrow, now);
    }

    public void recoverActiveWindows() {
        hotStateRepository.recoverActiveWindows(windowMapper.listActiveWindows());
    }

    public void retireSettledState(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        if (window != null && window.getStatus() == PromotionAuctionWindowStatus.SETTLED) {
            hotStateRepository.retireSettled(auctionWindowId);
        }
    }

    private void projectAuthorization(PromotionCampaign campaign,
                                      PromotionAuctionWindow window,
                                      PromotionBidEscrowRecord escrow,
                                      Instant now) {
        PromotionBidRoute route = route(campaign, window, escrow);
        PromotionProjectionCheckpointRecord checkpoint = checkpointMapper.findByAuctionWindowId(window.getId());
        hotStateRepository.initialize(route, checkpoint == null ? 0L : checkpoint.getLastDecisionVersion());
        hotStateRepository.projectAuthorization(route);
        routeRepository.save(route, now);
    }

    private PromotionBidRoute route(PromotionCampaign campaign,
                                    PromotionAuctionWindow window,
                                    PromotionBidEscrowRecord escrow) {
        PromotionBPrimeProperties.AuctionRules rules = properties.auctionRules(window.getResourceType())
                .withBoundAntiSnipe();
        return new PromotionBidRoute(
                campaign.getId(),
                escrow.getBidderUserId(),
                campaign.getPostId(),
                window.getId(),
                window.getResourceType().name(),
                window.getReservePrice(),
                escrow.getAuthorizedAmount(),
                window.getStatus().name(),
                window.getWindowEndAt(),
                window.getSlotCount(),
                rules.incrementCents(),
                rules.capPriceCents(),
                rules.extendWindowSec(),
                rules.extendSec(),
                rules.maxExtensions());
    }
}
