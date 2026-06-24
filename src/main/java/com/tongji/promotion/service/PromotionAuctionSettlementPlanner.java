package com.tongji.promotion.service;

import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PromotionAuctionSettlementPlanner {

    private final WalletProperties walletProperties;

    public PromotionAuctionSettlementPlanner(WalletProperties walletProperties) {
        this.walletProperties = walletProperties;
    }

    public PromotionAuctionSettlementPlan plan(PromotionAuctionWindow window, List<PromotionBid> bids) {
        List<PromotionBid> ranked = bids.stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                        .thenComparingLong(PromotionBid::getId))
                .toList();
        int winners = winnerCount(window, ranked);
        List<PromotionAuctionSettlementPlan.Winner> winnerFacts = new ArrayList<>();
        List<PromotionBid> losers = new ArrayList<>();
        List<PromotionAuctionSettlementPlan.WalletEffect> effects = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            PromotionBid bid = ranked.get(i);
            if (i < winners) {
                long clearingPrice = clearingPrice(window, ranked, i);
                winnerFacts.add(new PromotionAuctionSettlementPlan.Winner(bid, i, clearingPrice));
                effects.add(captureEffect(window, bid, clearingPrice));
                long releaseAmount = bid.getBidAmount() - clearingPrice;
                if (releaseAmount > 0) {
                    effects.add(releaseEffect(window, bid, releaseAmount));
                }
            } else {
                losers.add(bid);
                effects.add(releaseEffect(window, bid, bid.getBidAmount()));
            }
        }
        return new PromotionAuctionSettlementPlan(window, List.copyOf(winnerFacts), List.copyOf(losers), List.copyOf(effects));
    }

    public String businessRef(PromotionAuctionWindow window, PromotionBid bid, String effect) {
        return "promotion-bprime:" + window.getId() + ":" + bid.getCampaignId() + ":" + effect;
    }

    private int winnerCount(PromotionAuctionWindow window, List<PromotionBid> ranked) {
        return (int) ranked.stream()
                .takeWhile(bid -> bid.getBidAmount() >= window.getReservePrice())
                .limit(window.getSlotCount())
                .count();
    }

    private long clearingPrice(PromotionAuctionWindow window, List<PromotionBid> ranked, int slotIndex) {
        long nextBid = (slotIndex + 1 < ranked.size()) ? ranked.get(slotIndex + 1).getBidAmount() : window.getReservePrice();
        return Math.max(nextBid, window.getReservePrice());
    }

    private PromotionAuctionSettlementPlan.WalletEffect captureEffect(PromotionAuctionWindow window,
                                                                      PromotionBid bid,
                                                                      long amount) {
        return new PromotionAuctionSettlementPlan.WalletEffect(
                "CAPTURE",
                bid.getBidderUserId(),
                amount,
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION,
                WalletLedgerDirection.DEBIT,
                walletProperties.getPlatformUserId(),
                null,
                0L,
                -amount,
                0L,
                businessRef(window, bid, "capture")
        );
    }

    private PromotionAuctionSettlementPlan.WalletEffect releaseEffect(PromotionAuctionWindow window,
                                                                      PromotionBid bid,
                                                                      long amount) {
        return new PromotionAuctionSettlementPlan.WalletEffect(
                "RELEASE",
                bid.getBidderUserId(),
                amount,
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION,
                WalletLedgerDirection.CREDIT,
                null,
                null,
                amount,
                -amount,
                0L,
                businessRef(window, bid, "release")
        );
    }
}
