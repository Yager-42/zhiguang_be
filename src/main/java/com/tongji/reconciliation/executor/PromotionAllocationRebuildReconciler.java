package com.tongji.reconciliation.executor;

import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.service.PromotionAuctionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PromotionAllocationRebuildReconciler implements Reconciler {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final PromotionAuctionService auctionService;

    public PromotionAllocationRebuildReconciler(PromotionAuctionWindowMapper windowMapper,
                                                PromotionBidMapper bidMapper,
                                                PromotionSlotAllocationMapper allocationMapper,
                                                PromotionAuctionService auctionService) {
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.allocationMapper = allocationMapper;
        this.auctionService = auctionService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.PROMOTION_AUCTION_WINDOW.equals(task.getTargetType())) {
            throw new IllegalStateException("promotion_allocation_rebuild only supports promotion_auction_window target");
        }
        PromotionAuctionWindow window = windowMapper.findById(task.getTargetId());
        if (window == null) {
            throw new IllegalStateException("promotion auction window not found: " + task.getTargetId());
        }
        if (allocationMapper.countByAuctionWindowId(window.getId()) > 0) {
            return;
        }
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        List<PromotionBid> bids = bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt);
        Instant settledAt = window.getSettledAt() == null ? Instant.now() : window.getSettledAt();
        auctionService.settleWindow(window, bids, settledAt);
    }
}
