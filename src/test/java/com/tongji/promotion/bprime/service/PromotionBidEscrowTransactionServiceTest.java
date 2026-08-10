package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionDecisionPath;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PromotionBidEscrowTransactionServiceTest {

    private PromotionCampaignMapper campaignMapper;
    private PromotionAuctionWindowMapper windowMapper;
    private PromotionBidEscrowMapper escrowMapper;
    private WalletService walletService;
    private PromotionBPrimeProperties properties;
    private PromotionBidEscrowTransactionService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(PromotionCampaignMapper.class);
        windowMapper = mock(PromotionAuctionWindowMapper.class);
        escrowMapper = mock(PromotionBidEscrowMapper.class);
        walletService = mock(WalletService.class);
        IdService idService = mock(IdService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        service = new PromotionBidEscrowTransactionService(
                campaignMapper, windowMapper, escrowMapper, walletService, idService,
                reconciliationService, properties);

        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        PromotionCampaign campaign = PromotionCampaign.builder()
                .id(201L)
                .creatorUserId(42L)
                .postId(1001L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .status(PromotionCampaignStatus.ACTIVE)
                .startAt(now)
                .endAt(now.plusSeconds(600))
                .build();
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(now.minusSeconds(60))
                .windowEndAt(now.plusSeconds(60))
                .slotCount(2)
                .reservePrice(100L)
                .decisionPath(PromotionDecisionPath.REDIS_STREAM)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        ReconciliationTask task = ReconciliationTask.builder().id(901L).build();
        when(campaignMapper.findByIdForUpdate(201L)).thenReturn(campaign);
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window);
        when(idService.nextId(IdNamespace.PROMOTION_ESCROW)).thenReturn(501L);
        when(reconciliationService.createTaskIfAbsent(any(), any(), anyLong())).thenReturn(task);
    }

    @Test
    void retryingSameAuthorizationDoesNotHoldWalletTwice() {
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        when(escrowMapper.findByWindowAndCampaign(301L, 201L)).thenReturn(null);

        PromotionBidEscrowTransactionService.Authorization first = service.authorize(42L, 201L, 500L, now);
        PromotionBidEscrowRecord persisted = first.escrow();
        when(escrowMapper.findByWindowAndCampaign(301L, 201L)).thenReturn(persisted);

        PromotionBidEscrowTransactionService.Authorization retry = service.authorize(42L, 201L, 500L, now);

        verify(walletService).hold(42L, 500L, WalletLedgerReason.PROMOTION_BPRIME_HOLD,
                WalletBusinessType.PROMOTION, "promotion-bprime:escrow:301:201:authorize:500");
        verify(escrowMapper).insert(persisted);
        verify(escrowMapper, never()).increaseAuthorization(
                eq(301L), eq(201L), anyLong(), any(Instant.class), any(Instant.class));
        org.assertj.core.api.Assertions.assertThat(retry.escrow().getAuthorizedAmount()).isEqualTo(500L);
    }

    @Test
    void disabledHotPathPausesBeforeHoldingFunds() {
        properties.setEnabled(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.authorize(42L, 201L, 500L, Instant.parse("2026-08-09T12:00:00Z")))
                .isInstanceOf(com.tongji.common.exception.BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("PROMOTION_AUCTION_PAUSED");

        verifyNoInteractions(walletService);
    }
}
