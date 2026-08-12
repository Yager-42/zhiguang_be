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
import com.tongji.wallet.config.WalletProperties;
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

/**
 * LEGACY_BROKER 窗口结算测试（英式第一价格：单赢家 = 排名首位，付自己的最终出价，其余全额释放）。
 */
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
        service = new PromotionAuctionService(
                windowMapper,
                bidMapper,
                allocationMapper,
                walletService,
                idService,
                new PromotionAuctionSettlementPlanner(new WalletProperties())
        );
    }

    @Test
    void settlesSingleWinnerWithFirstPriceAndReleasesLosers() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 120L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 70L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        // 英式单赢家：首位（120）按第一价格 120 结算，其余全额释放
        verify(walletService).captureHoldToPlatform(42L, 120L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 201L, "capture"));
        verify(walletService).releaseHold(43L, 100L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, ref(301L, 202L, "release"));
        verify(walletService).releaseHold(44L, 70L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, ref(301L, 203L, "release"));
        verify(bidMapper).markWon(401L, 0, 120L);
        verify(bidMapper).markLost(402L);
        verify(bidMapper).markLost(403L);
        verify(allocationMapper).insert(any());
    }

    @Test
    void settlesTiedBidsWithStableIdOrdering() {
        // 并列最高出价：按 id 升序稳定排序，首位按第一价格结算，其余释放
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 100L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 80L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        verify(walletService).captureHoldToPlatform(42L, 100L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 201L, "capture"));
        verify(walletService).releaseHold(43L, 100L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, ref(301L, 202L, "release"));
        verify(bidMapper).markWon(401L, 0, 100L);
        verify(bidMapper).markLost(402L);
        verify(bidMapper).markLost(403L);
    }

    @Test
    void releasesEveryBidBelowWinnerRegardlessOfReserveFloor() {
        // 英式无 reserve 地板结算：首位（120）第一价格，其余（含低于 reserve 的 30）全额释放
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 120L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 30L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        verify(bidMapper).markLost(403L);
        verify(bidMapper, never()).markWon(eq(403L), anyInt(), anyLong());
        verify(walletService).releaseHold(44L, 30L, WalletLedgerReason.PROMOTION_BPRIME_RELEASE,
                WalletBusinessType.PROMOTION, ref(301L, 203L, "release"));
        verify(walletService).captureHoldToPlatform(42L, 120L, WalletLedgerReason.PROMOTION_BPRIME_CAPTURE,
                WalletBusinessType.PROMOTION, ref(301L, 201L, "capture"));
        verify(bidMapper).markWon(401L, 0, 120L);
    }

    @Test
    void noBidsSettleWithoutCaptureOrAllocation() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 1, 50L);

        service.settleWindow(window, List.of(), Instant.parse("2026-06-20T11:00:00Z"));

        verify(walletService, never()).captureHoldToPlatform(anyLong(), anyLong(), any(), any(), any());
        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), any());
        verify(allocationMapper, never()).insert(any());
        verify(windowMapper).markSettled(301L, Instant.parse("2026-06-20T11:00:00Z"));
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
