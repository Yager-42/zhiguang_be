package com.tongji.promotion.service;

import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;

import java.util.List;

/**
 * B' 结算预期事实：slot allocation 与 wallet effect 共用同一份 GSP 计算结果。
 */
public record PromotionAuctionSettlementPlan(
        PromotionAuctionWindow window,
        List<Winner> winners,
        List<PromotionBid> losers,
        List<WalletEffect> walletEffects
) {
    public record Winner(
            PromotionBid bid,
            int slotIndex,
            long clearingPrice
    ) {
    }

    public record WalletEffect(
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
}
