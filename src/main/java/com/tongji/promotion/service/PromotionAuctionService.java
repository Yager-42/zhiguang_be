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
import java.util.Comparator;
import java.util.List;

/**
 * 窗口关闭时的 GSP 结算：按出价降序（同价按 id 升序）取前 M 名占 M 个位，
 * 每位成交价 = 下一个有效出价与保留价的较高者；winner 扣成交价、释放超额冻结，loser 全额释放。
 * <p>只有不低于保留价的「有效出价」才能中标：低于保留价的出价不占位、全额释放。
 * 由于 winner 必为有效出价（bidAmount &ge; reserve），且排序保证 nextBid &le; winner.bidAmount，
 * 故 clearingPrice = max(nextBid, reserve) &le; winner.bidAmount，绝不会扣超过已冻结额。</p>
 * <p>结算结果落 {@link PromotionSlotAllocation}，有效期 = 结算窗口结束后的下一个窗口周期。</p>
 */
@Service
public class PromotionAuctionService {

    private static final String CAPTURE_REF = "promotion-settle:bid:";
    private static final String RELEASE_REF = "promotion-settle:bid:";

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletService walletService;
    private final IdService idService;

    public PromotionAuctionService(PromotionAuctionWindowMapper windowMapper,
                                   PromotionBidMapper bidMapper,
                                   PromotionSlotAllocationMapper allocationMapper,
                                   WalletService walletService,
                                   IdService idService) {
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.allocationMapper = allocationMapper;
        this.walletService = walletService;
        this.idService = idService;
    }

    @Transactional
    public void settleWindow(PromotionAuctionWindow window, List<PromotionBid> bids, Instant settledAt) {
        List<PromotionBid> ranked = bids.stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                        .thenComparingLong(PromotionBid::getId))
                .toList();
        long reserve = window.getReservePrice();
        // 有效出价（>= reserve）在降序排名中是前缀；取前 slotCount 个有效出价为 winner，其余全为 loser
        int winners = (int) ranked.stream()
                .takeWhile(bid -> bid.getBidAmount() >= reserve)
                .limit(window.getSlotCount())
                .count();
        for (int i = 0; i < ranked.size(); i++) {
            PromotionBid bid = ranked.get(i);
            if (i < winners) {
                settleWinner(window, bid, i, ranked, settledAt);
            } else {
                settleLoser(bid);
            }
        }
        windowMapper.markSettled(window.getId(), settledAt);
    }

    private void settleWinner(PromotionAuctionWindow window, PromotionBid bid, int slotIndex,
                              List<PromotionBid> ranked, Instant settledAt) {
        long nextBid = (slotIndex + 1 < ranked.size()) ? ranked.get(slotIndex + 1).getBidAmount() : window.getReservePrice();
        long clearingPrice = Math.max(nextBid, window.getReservePrice());
        long releaseAmount = bid.getBidAmount() - clearingPrice;

        // clearingPrice <= bidAmount 由「仅有效出价（>= reserve）可中标 + 排序保证 nextBid <= winner.bidAmount」
        // 结构性保证（见类注释），并由边界测试守护；此处直接扣成交价，不做掩盖性 clamp。
        walletService.captureHoldToPlatform(bid.getBidderUserId(), clearingPrice,
                WalletBusinessType.PROMOTION, CAPTURE_REF + bid.getId() + ":capture");
        if (releaseAmount > 0) {
            walletService.releaseHold(bid.getBidderUserId(), releaseAmount,
                    WalletLedgerReason.PROMOTION_BID_RELEASE, WalletBusinessType.PROMOTION,
                    RELEASE_REF + bid.getId() + ":release");
        }
        bidMapper.markWon(bid.getId(), slotIndex, clearingPrice);
        allocationMapper.insert(buildAllocation(window, bid, slotIndex, clearingPrice, settledAt));
    }

    private void settleLoser(PromotionBid bid) {
        walletService.releaseHold(bid.getBidderUserId(), bid.getBidAmount(),
                WalletLedgerReason.PROMOTION_BID_RELEASE, WalletBusinessType.PROMOTION,
                RELEASE_REF + bid.getId() + ":release");
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
