package com.tongji.reconciliation.executor;

import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 重放 MySQL 已提交但尚未同步到 Redis 的保证金授权投影。
 */
@Component
public class PromotionEscrowRedisProjectionReconciler implements Reconciler {

    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionCampaignMapper campaignMapper;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionAuctionHotStateRepository hotStateRepository;
    private final PromotionBidRouteRepository routeRepository;

    public PromotionEscrowRedisProjectionReconciler(PromotionBidEscrowMapper escrowMapper,
                                                     PromotionAuctionWindowMapper windowMapper,
                                                     PromotionCampaignMapper campaignMapper,
                                                     PromotionProjectionCheckpointMapper checkpointMapper,
                                                     PromotionAuctionHotStateRepository hotStateRepository,
                                                     PromotionBidRouteRepository routeRepository) {
        this.escrowMapper = escrowMapper;
        this.windowMapper = windowMapper;
        this.campaignMapper = campaignMapper;
        this.checkpointMapper = checkpointMapper;
        this.hotStateRepository = hotStateRepository;
        this.routeRepository = routeRepository;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.PROMOTION_ESCROW_REDIS_PROJECTION;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        PromotionBidEscrowRecord escrow = escrowMapper.findById(task.getTargetId());
        if (escrow == null) {
            throw new IllegalStateException("promotion bid escrow not found: " + task.getTargetId());
        }
        PromotionAuctionWindow window = windowMapper.findById(escrow.getAuctionWindowId());
        PromotionCampaign campaign = campaignMapper.findById(escrow.getCampaignId());
        if (window == null || campaign == null) {
            throw new IllegalStateException("promotion escrow projection source is incomplete: " + task.getTargetId());
        }
        PromotionBidRoute route = new PromotionBidRoute(
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
                window.getDecisionPath().name());
        PromotionProjectionCheckpointRecord checkpoint = checkpointMapper.findByAuctionWindowId(window.getId());
        hotStateRepository.initialize(route, checkpoint == null ? 0L : checkpoint.getLastDecisionVersion());
        hotStateRepository.projectAuthorization(route);
        routeRepository.save(route, Instant.now());
    }
}
