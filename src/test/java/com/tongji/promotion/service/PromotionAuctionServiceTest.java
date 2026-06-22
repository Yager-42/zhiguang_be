package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionServiceTest {

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    @Mock
    private PromotionBidMapper bidMapper;

    @Mock
    private PromotionSlotAllocationMapper allocationMapper;

    @Mock
    private WalletService walletService;

    @Mock
    private IdService idService;

    private PromotionAuctionService service;

    @BeforeEach
    void setUp() {
        service = new PromotionAuctionService(windowMapper, bidMapper, allocationMapper, walletService, idService);
    }

    @Test
    void settlesTwoSlotWindowWithGspPricing() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 120L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 70L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L, 502L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        // 2 槽位 GSP：top2 中标，成交价 = 下一个有效出价与保留价的较高者
        verify(walletService).captureHoldToPlatform(42L, 100L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 201L, "capture"));
        verify(walletService).captureHoldToPlatform(43L, 70L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 202L, "capture"));
        // winner 释放超额冻结
        verify(walletService).releaseHold(42L, 20L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION, ref(301L, 201L, "release"));
        verify(walletService).releaseHold(43L, 30L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION, ref(301L, 202L, "release"));
        // loser 全额释放
        verify(walletService).releaseHold(44L, 70L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION, ref(301L, 203L, "release"));
        verify(bidMapper).markWon(401L, 0, 100L);
        verify(bidMapper).markWon(402L, 1, 70L);
        verify(bidMapper).markLost(403L);
    }

    @Test
    void settlesTiedBidsWithStableIdOrdering() {
        // 两个并列最高出价：按 id 升序稳定排序，前者付并列价、后者付下一个有效出价
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 100L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 80L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L, 502L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        // 401（id 较小）成交价 = 并列出价 100；402 成交价 = 下一个有效出价 80
        verify(walletService).captureHoldToPlatform(42L, 100L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 201L, "capture"));
        verify(walletService).captureHoldToPlatform(43L, 80L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 202L, "capture"));
        verify(bidMapper).markWon(401L, 0, 100L);
        verify(bidMapper).markWon(402L, 1, 80L);
        verify(bidMapper).markLost(403L);
    }

    @Test
    void dropsBelowReserveBidsAndAppliesReserveFloorAsClearingPrice() {
        // 低于保留价的出价不占位、全额释放；末位 winner 成交价取保留价下限
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 120L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 30L)   // 低于 reserve 50
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L, 502L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        // 403 低于保留价 → 落败全额释放，绝不被结算成 winner
        verify(bidMapper).markLost(403L);
        verify(bidMapper, never()).markWon(eq(403L), anyInt(), anyLong());
        verify(walletService).releaseHold(44L, 30L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION, ref(301L, 203L, "release"));
        // 末位 winner 402 的下一个排名出价（30）低于保留价 → 成交价取保留价 50（<= 其出价 100，不超额）
        verify(walletService).captureHoldToPlatform(43L, 50L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 202L, "capture"));
        verify(bidMapper).markWon(402L, 1, 50L);
    }

    @Test
    void capturesNothingAndReleasesAllWhenEveryBidBelowReserve() {
        // 全部出价低于保留价：无人中标，不扣任何人一分钱（边界：不会扣超过 hold）
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 1, 50L);
        List<PromotionBid> bids = List.of(bid(401L, 201L, 42L, 30L));

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        verify(walletService).releaseHold(42L, 30L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION, ref(301L, 201L, "release"));
        verify(bidMapper).markLost(401L);
        verify(walletService, never()).captureHoldToPlatform(anyLong(), anyLong(), any(), any());
        verify(allocationMapper, never()).insert(any());
    }

    private PromotionAuctionWindow window(long id, PromotionResourceType type, int slotCount, long reservePrice) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(type)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(slotCount)
                .reservePrice(reservePrice)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse("2026-06-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:00:00Z"))
                .build();
    }

    private PromotionBid bid(long id, long campaignId, long bidderUserId, long bidAmount) {
        return PromotionBid.builder()
                .id(id)
                .campaignId(campaignId)
                .auctionWindowId(301L)
                .bidderUserId(bidderUserId)
                .bidAmount(bidAmount)
                .walletBusinessRef("promotion-bid:" + id)
                .status(PromotionBidStatus.ACTIVE)
                .postId(5000L + id)
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build();
    }

    private String ref(long auctionWindowId, long campaignId, String effect) {
        return "promotion-bprime:" + auctionWindowId + ":" + campaignId + ":" + effect;
    }
}
