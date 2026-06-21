package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidBoostCampaignServiceTest {

    @Mock
    private PaidBoostCampaignMapper campaignMapper;

    @Mock
    private PaidBoostQuoteService quoteService;

    @Mock
    private WalletService walletService;

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private IdService idService;

    private PaidBoostCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PaidBoostCampaignService(campaignMapper, quoteService, walletService, knowPostMapper, idService);
    }

    @Test
    void createCampaignHoldsBudgetAndPersistsActiveCampaign() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(1L);
        when(quoteService.quote(PaidBoostChannel.HOME_RECOMMENDATION, 30L)).thenReturn(20L);

        PaidBoostCampaign campaign = service.createCampaign(42L, 1001L, PaidBoostChannel.HOME_RECOMMENDATION,
                30L, 2L, 100L,
                Instant.parse("2026-06-21T10:00:00Z"),
                Instant.parse("2026-06-21T12:00:00Z"));

        verify(walletService).hold(42L, 100L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, "paid-boost:1:reserve");
        verify(quoteService).quote(PaidBoostChannel.HOME_RECOMMENDATION, 30L);
        verify(campaignMapper).insert(any(PaidBoostCampaign.class));
        assertThat(campaign.getBidAmount()).isEqualTo(30L);
        assertThat(campaign.getBoostValue()).isEqualTo(20L);
        assertThat(campaign.getStatus()).isEqualTo(PaidBoostCampaignStatus.ACTIVE);
    }

    @Test
    void createCampaignRejectsPostNotOwnedByCreator() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 99L, "published", "public"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PaidBoostChannel.HOME_RECOMMENDATION,
                30L, 2L, 100L,
                Instant.parse("2026-06-21T10:00:00Z"),
                Instant.parse("2026-06-21T12:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAID_BOOST_POST_NOT_ELIGIBLE);
        verify(walletService, org.mockito.Mockito.never()).hold(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void homeRecommendationRequiresPublicVisibility() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PaidBoostChannel.HOME_RECOMMENDATION,
                30L, 2L, 100L,
                Instant.parse("2026-06-21T10:00:00Z"),
                Instant.parse("2026-06-21T12:00:00Z")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void followDeliveryAllowsFollowersVisible() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(2L);
        when(quoteService.quote(PaidBoostChannel.FOLLOW_DELIVERY, 30L)).thenReturn(30L);

        PaidBoostCampaign campaign = service.createCampaign(42L, 1001L, PaidBoostChannel.FOLLOW_DELIVERY,
                30L, 2L, 100L,
                Instant.parse("2026-06-21T10:00:00Z"),
                Instant.parse("2026-06-21T12:00:00Z"));

        assertThat(campaign.getChannel()).isEqualTo(PaidBoostChannel.FOLLOW_DELIVERY);
        assertThat(campaign.getStatus()).isEqualTo(PaidBoostCampaignStatus.ACTIVE);
        verify(walletService).hold(eq(42L), eq(100L), any(), any(), eq("paid-boost:2:reserve"));
    }

    private KnowPost post(long id, long creatorId, String status, String visible) {
        return KnowPost.builder().id(id).creatorId(creatorId).status(status).visible(visible).build();
    }
}
