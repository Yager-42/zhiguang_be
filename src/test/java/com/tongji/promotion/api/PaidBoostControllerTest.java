package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.CreatePaidBoostCampaignRequest;
import com.tongji.promotion.api.dto.PaidBoostCampaignResponse;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.service.PaidBoostCampaignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidBoostControllerTest {

    @Mock
    private PaidBoostCampaignService campaignService;

    @Mock
    private JwtService jwtService;

    private PaidBoostController controller;

    @BeforeEach
    void setUp() {
        controller = new PaidBoostController(campaignService, jwtService);
    }

    @Test
    void createCampaignDelegatesWithExtractedUser() {
        Instant start = Instant.parse("2026-06-21T10:00:00Z");
        Instant end = Instant.parse("2026-06-21T12:00:00Z");
        when(jwtService.extractUserId(any())).thenReturn(42L);
        when(campaignService.createCampaign(eq(42L), eq(1001L), eq(PaidBoostChannel.HOME_RECOMMENDATION),
                eq(30L), eq(2L), eq(100L), eq(start), eq(end)))
                .thenReturn(campaign(1L, 42L, 1001L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 20L));

        PaidBoostCampaignResponse response = controller.createCampaign(
                new CreatePaidBoostCampaignRequest(1001L, "home_recommendation", 30L, 2L, 100L, start, end), null);

        verify(campaignService).createCampaign(eq(42L), eq(1001L), eq(PaidBoostChannel.HOME_RECOMMENDATION),
                eq(30L), eq(2L), eq(100L), eq(start), eq(end));
        assertThat(response.channel()).isEqualTo("home_recommendation");
        assertThat(response.effectiveBoostValue()).isEqualTo(20L);
        assertThat(response.bidAmount()).isEqualTo(30L);
    }

    @Test
    void invalidChannelThrowsPaidBoostInvalidChannel() {
        when(jwtService.extractUserId(any())).thenReturn(42L);

        assertThatThrownBy(() -> controller.createCampaign(
                new CreatePaidBoostCampaignRequest(1001L, "search_top_slot", 30L, 2L, 100L,
                        Instant.parse("2026-06-21T10:00:00Z"),
                        Instant.parse("2026-06-21T12:00:00Z")), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAID_BOOST_INVALID_CHANNEL);
    }

    private PaidBoostCampaign campaign(long id, long creator, long post, PaidBoostChannel channel,
                                       long bidAmount, long boostValue) {
        return PaidBoostCampaign.builder()
                .id(id).creatorUserId(creator).postId(post).channel(channel)
                .bidAmount(bidAmount).boostValue(boostValue).unitPrice(2L).budgetTotal(100L).budgetConsumed(0L)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z"))
                .endAt(Instant.parse("2026-06-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
    }
}
