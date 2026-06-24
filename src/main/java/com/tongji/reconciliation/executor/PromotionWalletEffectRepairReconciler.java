package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Component;

@Component
public class PromotionWalletEffectRepairReconciler implements Reconciler {

    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    public PromotionWalletEffectRepairReconciler(WalletService walletService, ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.PROMOTION_AUCTION_WINDOW.equals(task.getTargetType())) {
            throw new IllegalStateException("promotion_wallet_effect_repair only supports promotion_auction_window target");
        }
        PromotionWalletEffectRepairPayload payload = readPayload(task);
        try {
            if ("CAPTURE".equals(payload.effectType())) {
                walletService.captureHoldToPlatform(payload.ownerUserId(), payload.amount(), payload.reason(),
                        payload.businessType(), payload.businessRef());
                return;
            }
            if ("RELEASE".equals(payload.effectType())) {
                walletService.releaseHold(payload.ownerUserId(), payload.amount(), payload.reason(),
                        payload.businessType(), payload.businessRef());
                return;
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.WALLET_DUPLICATE_BUSINESS_REF) {
                throw new NonRetryableReconciliationException(
                        "promotion wallet effect conflicts with existing businessRef " + payload.businessRef(), e);
            }
            throw e;
        }
        throw new IllegalStateException("Unsupported promotion wallet repair effect: " + payload.effectType());
    }

    private PromotionWalletEffectRepairPayload readPayload(ReconciliationTask task) {
        if (task.getTaskPayload() == null || task.getTaskPayload().isBlank()) {
            throw new IllegalStateException("promotion_wallet_effect_repair requires payload");
        }
        try {
            return objectMapper.readValue(task.getTaskPayload(), PromotionWalletEffectRepairPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid promotion wallet effect repair payload", e);
        }
    }
}
