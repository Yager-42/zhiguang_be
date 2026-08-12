package com.tongji.promotion.settlement;

import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record PromotionAuctionSettlementFacts(
        PromotionAuctionWindow window,
        TerminalKind terminalKind,
        Optional<Winner> winner,
        List<PromotionBid> losers,
        List<WalletEffect> walletEffects,
        Optional<Allocation> allocation
) {
    public enum TerminalKind {
        SOLD,
        NO_BID
    }

    public record Winner(PromotionBid bid, long campaignId, long winningAmount) {
    }

    public record Allocation(
            PromotionResourceType resourceType,
            int slotIndex,
            long campaignId,
            long postId,
            long bidderUserId,
            long clearingPrice,
            Instant allocationStartAt,
            Instant allocationEndAt
    ) {
    }

    public record WalletEffect(
            EffectType effectType,
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

    public enum EffectType {
        CAPTURE,
        RELEASE
    }
}
