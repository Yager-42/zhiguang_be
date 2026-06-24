package com.tongji.reconciliation.executor;

import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;

public record PromotionWalletEffectRepairPayload(
        String effectType,
        long ownerUserId,
        long amount,
        WalletLedgerReason reason,
        WalletBusinessType businessType,
        WalletLedgerDirection direction,
        Long counterpartyUserId,
        Long escrowId,
        long availableDelta,
        long heldDelta,
        long escrowedDelta,
        String businessRef
) {
}
