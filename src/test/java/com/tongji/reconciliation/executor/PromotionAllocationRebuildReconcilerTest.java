package com.tongji.reconciliation.executor;

import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionAllocationRebuildReconcilerTest {

    @Test
    void delegatesAllocationOnlyRebuildToSettlementModule() {
        PromotionAuctionSettlementModule settlementModule = mock(PromotionAuctionSettlementModule.class);
        PromotionAllocationRebuildReconciler reconciler = new PromotionAllocationRebuildReconciler(settlementModule);

        reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW)
                .targetId(301L)
                .build());

        verify(settlementModule).rebuildMissingAllocation(301L);
    }
}
