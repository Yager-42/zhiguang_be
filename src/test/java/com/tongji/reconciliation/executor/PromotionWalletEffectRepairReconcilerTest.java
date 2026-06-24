package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionWalletEffectRepairReconcilerTest {

    @Test
    void capturePayloadCallsWalletCaptureIdempotentEntry() throws Exception {
        WalletService walletService = mock(WalletService.class);
        PromotionWalletEffectRepairReconciler reconciler = new PromotionWalletEffectRepairReconciler(
                walletService,
                new ObjectMapper().findAndRegisterModules()
        );

        reconciler.reconcile(task(new PromotionWalletEffectRepairPayload(
                "CAPTURE",
                42L,
                100L,
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION,
                WalletLedgerDirection.DEBIT,
                0L,
                null,
                0L,
                -100L,
                0L,
                "promotion-bprime:301:201:capture"
        )));

        verify(walletService).captureHoldToPlatform(42L, 100L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, "promotion-bprime:301:201:capture");
    }

    @Test
    void releasePayloadCallsWalletReleaseIdempotentEntry() throws Exception {
        WalletService walletService = mock(WalletService.class);
        PromotionWalletEffectRepairReconciler reconciler = new PromotionWalletEffectRepairReconciler(
                walletService,
                new ObjectMapper().findAndRegisterModules()
        );

        reconciler.reconcile(task(new PromotionWalletEffectRepairPayload(
                "RELEASE",
                42L,
                20L,
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION,
                WalletLedgerDirection.CREDIT,
                null,
                null,
                20L,
                -20L,
                0L,
                "promotion-bprime:301:201:release"
        )));

        verify(walletService).releaseHold(42L, 20L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, "promotion-bprime:301:201:release");
    }

    private ReconciliationTask task(PromotionWalletEffectRepairPayload payload) throws Exception {
        return ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_AUCTION_WINDOW)
                .targetId(301L)
                .taskPayload(new ObjectMapper().findAndRegisterModules().writeValueAsString(payload))
                .build();
    }
}
