package com.tongji.promotion.service;

import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.mapper.PaidBoostDeliveryMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.model.PaidBoostDelivery;
import com.tongji.promotion.model.PaidBoostDeliveryStatus;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidBoostSettlementServiceTest {

    @Mock
    private PaidBoostDeliveryMapper deliveryMapper;

    @Mock
    private PaidBoostCampaignMapper campaignMapper;

    @Mock
    private WalletService walletService;

    private PaidBoostSettlementService service;

    private final PaidBoostProperties properties = new PaidBoostProperties();

    @BeforeEach
    void setUp() {
        service = new PaidBoostSettlementService(deliveryMapper, campaignMapper, walletService, properties);
    }

    @Test
    void settlesPendingDeliveryByCapturingHeldBudget() {
        Instant now = Instant.parse("2026-06-21T10:05:00Z");
        PaidBoostDelivery delivery = PaidBoostDelivery.builder()
                .id(11L).campaignId(1L).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .postId(1001L).viewerUserId(77L)
                .deliveryBucketStartAt(Instant.parse("2026-06-21T10:00:00Z"))
                .deliveryCount(1).unitPriceSnapshot(2L).capturedAmount(0L)
                .settleBusinessRef("paid-boost:1:spend:202606211000:77")
                .status(PaidBoostDeliveryStatus.PENDING).settledAt(null)
                .createdAt(Instant.parse("2026-06-21T10:00:05Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:05Z"))
                .build();
        when(deliveryMapper.listPendingBefore(any(), eq(100))).thenReturn(List.of(delivery));
        when(campaignMapper.findById(1L)).thenReturn(campaign(1L, 42L, 100L, 0L));

        service.settlePendingDeliveries(now, 100);

        verify(walletService).captureHoldToPlatform(42L, 2L,
                WalletLedgerReason.PAID_BOOST_CAPTURE, WalletBusinessType.PROMOTION,
                "paid-boost:1:spend:202606211000:77");
        verify(campaignMapper).increaseBudgetConsumed(1L, 2L, now);
        verify(deliveryMapper).markSettledWithAmount(11L, 2L, now);
    }

    @Test
    void settleCapturesAtMostRemainingBudget() {
        Instant now = Instant.parse("2026-06-21T10:05:00Z");
        PaidBoostDelivery delivery = delivery(11L, 1L, 77L, "paid-boost:1:spend:202606211000:77", 5, 2L);
        when(deliveryMapper.listPendingBefore(any(), eq(100))).thenReturn(List.of(delivery));
        // 预算只剩 7，planned = 5*2 = 10 → capture min(7, 10) = 7
        when(campaignMapper.findById(1L)).thenReturn(campaign(1L, 42L, 100L, 93L));

        service.settlePendingDeliveries(now, 100);

        verify(walletService).captureHoldToPlatform(eq(42L), eq(7L),
                eq(WalletLedgerReason.PAID_BOOST_CAPTURE), eq(WalletBusinessType.PROMOTION),
                eq("paid-boost:1:spend:202606211000:77"));
        verify(campaignMapper).increaseBudgetConsumed(1L, 7L, now);
        verify(deliveryMapper).markSettledWithAmount(11L, 7L, now);
    }

    @Test
    void closesExpiredCampaignAndReleasesRemainingBudget() {
        Instant now = Instant.parse("2026-06-21T13:00:00Z");
        PaidBoostCampaign campaign = campaign(1L, 42L, 100L, 40L);
        when(campaignMapper.listClosable(now, 100)).thenReturn(List.of(campaign));

        service.closeExpiredCampaigns(now, 100);

        verify(walletService).releaseHold(42L, 60L,
                WalletLedgerReason.PAID_BOOST_RELEASE, WalletBusinessType.PROMOTION, "paid-boost:1:release");
        verify(campaignMapper).markClosed(1L, now, now);
    }

    @Test
    void closesExpiredCampaignWithNoRemainingSkipsRelease() {
        Instant now = Instant.parse("2026-06-21T13:00:00Z");
        PaidBoostCampaign campaign = campaign(1L, 42L, 100L, 100L);
        when(campaignMapper.listClosable(now, 100)).thenReturn(List.of(campaign));

        service.closeExpiredCampaigns(now, 100);

        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), any());
        verify(campaignMapper).markClosed(1L, now, now);
    }

    private PaidBoostDelivery delivery(long id, long campaignId, long viewer, String ref, int count, long unitPrice) {
        return PaidBoostDelivery.builder()
                .id(id).campaignId(campaignId).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .postId(1001L).viewerUserId(viewer)
                .deliveryBucketStartAt(Instant.parse("2026-06-21T10:00:00Z"))
                .deliveryCount(count).unitPriceSnapshot(unitPrice).capturedAmount(0L)
                .settleBusinessRef(ref).status(PaidBoostDeliveryStatus.PENDING).settledAt(null)
                .createdAt(Instant.parse("2026-06-21T10:00:05Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:05Z"))
                .build();
    }

    private PaidBoostCampaign campaign(long id, long creator, long budgetTotal, long consumed) {
        return PaidBoostCampaign.builder()
                .id(id).creatorUserId(creator).postId(1000L + id).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .bidAmount(30L).boostValue(30L).unitPrice(2L).budgetTotal(budgetTotal).budgetConsumed(consumed)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z"))
                .endAt(Instant.parse("2026-06-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
    }
}
