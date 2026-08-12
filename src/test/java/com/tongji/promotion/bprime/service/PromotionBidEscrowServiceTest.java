package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionAuctionUnavailableException;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionBidEscrowServiceTest {

    private PromotionBidEscrowTransactionService transactionService;
    private PromotionBidRouteRepository routeRepository;
    private PromotionAuctionHotStateRepository hotStateRepository;
    private ReconciliationTaskMapper reconciliationTaskMapper;
    private PromotionBidEscrowService service;
    private PromotionBidEscrowTransactionService.Authorization authorization;

    @BeforeEach
    void setUp() {
        transactionService = mock(PromotionBidEscrowTransactionService.class);
        routeRepository = mock(PromotionBidRouteRepository.class);
        PromotionProjectionCheckpointMapper checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        hotStateRepository = mock(PromotionAuctionHotStateRepository.class);
        reconciliationTaskMapper = mock(ReconciliationTaskMapper.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        service = new PromotionBidEscrowService(transactionService, routeRepository, checkpointMapper,
                hotStateRepository, reconciliationTaskMapper, properties);

        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        PromotionCampaign campaign = PromotionCampaign.builder()
                .id(201L).creatorUserId(42L).postId(1001L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT).build();
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L).resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowEndAt(now.plusSeconds(60)).slotCount(2).reservePrice(100L)
                .status(PromotionAuctionWindowStatus.OPEN).build();
        PromotionBidEscrowRecord escrow = PromotionBidEscrowRecord.builder()
                .id(501L).auctionWindowId(301L).campaignId(201L).bidderUserId(42L)
                .authorizedAmount(500L).currentHold(0L).status("ACTIVE").build();
        authorization = new PromotionBidEscrowTransactionService.Authorization(
                campaign, window, escrow, ReconciliationTask.builder().id(901L).build());
        when(transactionService.authorize(42L, 201L, 500L, now)).thenReturn(authorization);
    }

    @Test
    void failedRedisProjectionKeepsTaskPendingAndSameAuthorizationCanRetry() {
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        doThrow(new PromotionAuctionUnavailableException("redis down"))
                .doNothing()
                .when(hotStateRepository).projectAuthorization(any());

        assertThatThrownBy(() -> service.authorize(42L, 201L, 500L, now))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("PROMOTION_AUCTION_PAUSED");
        verify(reconciliationTaskMapper, never()).markSucceeded(901L, 0L);

        PromotionBidEscrowAuthorizationResponse retry = service.authorize(42L, 201L, 500L, now);

        assertThat(retry.authorizedAmount()).isEqualTo(500L);
        verify(transactionService, org.mockito.Mockito.times(2)).authorize(42L, 201L, 500L, now);
        verify(reconciliationTaskMapper).markSucceeded(901L, 0L);
        verify(routeRepository).save(any(), org.mockito.ArgumentMatchers.eq(now));
    }
}
