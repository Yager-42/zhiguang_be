package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 窗口关闭时的英式第一价格结算（LEGACY_BROKER 排空窗口；REDIS_STREAM 窗口由
 * PromotionDecisionProjectionService.settleWindow 结算）：按出价降序（同价按 id 升序）取首位为唯一赢家，
 * 赢家付自己的最终出价（= 终态共享当前价），loser 全额释放。
 * <p>由于英式首价恒 ≥ reserve+increment，第一价格 ≤ winner 冻结额，绝不会扣超额。</p>
 * <p>结算结果落 {@link PromotionSlotAllocation}，有效期 = 结算窗口结束后的下一个窗口周期。</p>
 */
@Service
public class PromotionAuctionService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletService walletService;
    private final IdService idService;
    private final PromotionAuctionSettlementPlanner settlementPlanner;

    public PromotionAuctionService(PromotionAuctionWindowMapper windowMapper,
                                   PromotionBidMapper bidMapper,
                                   PromotionSlotAllocationMapper allocationMapper,
                                   WalletService walletService,
                                   IdService idService,
                                   PromotionAuctionSettlementPlanner settlementPlanner) {
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.allocationMapper = allocationMapper;
        this.walletService = walletService;
        this.idService = idService;
        this.settlementPlanner = settlementPlanner;
    }

    @Transactional
    public void settleWindow(PromotionAuctionWindow window, List<PromotionBid> bids, Instant settledAt) {
        PromotionAuctionSettlementPlan plan = settlementPlanner.plan(window, bids);
        for (PromotionAuctionSettlementPlan.Winner winner : plan.winners()) {
            settleWinner(window, winner, settledAt);
        }
        for (PromotionBid loser : plan.losers()) {
            settleLoser(window, loser);
        }
        windowMapper.markSettled(window.getId(), settledAt);
    }

    private void settleWinner(PromotionAuctionWindow window, PromotionAuctionSettlementPlan.Winner winner, Instant settledAt) {
        PromotionBid bid = winner.bid();
        walletService.captureHoldToPlatform(bid.getBidderUserId(), winner.clearingPrice(),
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                settlementPlanner.businessRef(window, bid, "capture"));
        long releaseAmount = bid.getBidAmount() - winner.clearingPrice();
        if (releaseAmount > 0) {
            walletService.releaseHold(bid.getBidderUserId(), releaseAmount,
                    WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                    settlementPlanner.businessRef(window, bid, "release"));
        }
        bidMapper.markWon(bid.getId(), winner.slotIndex(), winner.clearingPrice());
        allocationMapper.insert(buildAllocation(window, bid, winner.slotIndex(), winner.clearingPrice(), settledAt));
    }

    private void settleLoser(PromotionAuctionWindow window, PromotionBid bid) {
        walletService.releaseHold(bid.getBidderUserId(), bid.getBidAmount(),
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                settlementPlanner.businessRef(window, bid, "release"));
        bidMapper.markLost(bid.getId());
    }

    private PromotionSlotAllocation buildAllocation(PromotionAuctionWindow window, PromotionBid bid,
                                                    int slotIndex, long clearingPrice, Instant settledAt) {
        // 有效期 = 结算窗口结束后的下一个窗口周期（N 窗收单、N+1 窗展示）
        Instant startAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant endAt = startAt.plusSeconds(spanSeconds);
        return PromotionSlotAllocation.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .auctionWindowId(window.getId())
                .resourceType(window.getResourceType())
                .slotIndex(slotIndex)
                .campaignId(bid.getCampaignId())
                .postId(bid.getPostId())
                .bidderUserId(bid.getBidderUserId())
                .clearingPrice(clearingPrice)
                .allocationStartAt(startAt)
                .allocationEndAt(endAt)
                .createdAt(settledAt)
                .build();
    }
}
