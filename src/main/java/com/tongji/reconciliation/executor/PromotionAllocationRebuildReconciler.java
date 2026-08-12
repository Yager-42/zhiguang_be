package com.tongji.reconciliation.executor;

import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

@Component
public class PromotionAllocationRebuildReconciler implements Reconciler {

    private final PromotionAuctionSettlementModule settlementModule;

    public PromotionAllocationRebuildReconciler(PromotionAuctionSettlementModule settlementModule) {
        this.settlementModule = settlementModule;
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
        try {
            settlementModule.rebuildMissingAllocation(task.getTargetId());
        } catch (IllegalStateException exception) {
            throw new NonRetryableReconciliationException(exception.getMessage(), exception);
        }
    }
}
