package com.tongji.reconciliation.executor;

import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.service.PromotionAuctionHotStateLifecycle;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;


/**
 * 重放 MySQL 已提交但尚未同步到 Redis 的保证金授权投影。
 */
@Component
public class PromotionEscrowRedisProjectionReconciler implements Reconciler {

    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;

    public PromotionEscrowRedisProjectionReconciler(PromotionBidEscrowMapper escrowMapper,
                                                     PromotionAuctionWindowMapper windowMapper,
                                                     PromotionCampaignMapper campaignMapper,
                                                     PromotionAuctionHotStateLifecycle hotStateLifecycle) {
        this.escrowMapper = escrowMapper;
        this.windowMapper = windowMapper;
        this.campaignMapper = campaignMapper;
        this.hotStateLifecycle = hotStateLifecycle;
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
        hotStateLifecycle.restoreAuthorization(campaign, window, escrow, java.time.Instant.now());
    }
}
