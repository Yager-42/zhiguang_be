package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
import com.tongji.promotion.bprime.redis.PromotionBidRouteRepository;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAuctionHotStateLifecycleTest {

    private PromotionProjectionCheckpointMapper checkpointMapper;
    private PromotionAuctionHotStateRepository hotStateRepository;
    private PromotionBidRouteRepository routeRepository;
    private ReconciliationTaskMapper reconciliationTaskMapper;
    private PromotionAuctionHotStateLifecycle lifecycle;
    private PromotionCampaign campaign;
    private PromotionAuctionWindow window;
    private PromotionBidEscrowRecord escrow;
    private Instant now;

    @BeforeEach
    void setUp() {
        PromotionAuctionWindowMapper windowMapper = mock(PromotionAuctionWindowMapper.class);
        checkpointMapper = mock(PromotionProjectionCheckpointMapper.class);
        hotStateRepository = mock(PromotionAuctionHotStateRepository.class);
        routeRepository = mock(PromotionBidRouteRepository.class);
        reconciliationTaskMapper = mock(ReconciliationTaskMapper.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        lifecycle = new PromotionAuctionHotStateLifecycle(windowMapper, checkpointMapper, hotStateRepository,
                routeRepository, reconciliationTaskMapper, properties);
        now = Instant.parse("2026-08-13T10:00:00Z");
        campaign = PromotionCampaign.builder()
                .id(201L)
                .creatorUserId(42L)
                .postId(1001L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .build();
        window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .reservePrice(100L)
                .slotCount(2)
                .status(PromotionAuctionWindowStatus.OPEN)
                .windowEndAt(now.plusSeconds(60))
                .build();
        escrow = PromotionBidEscrowRecord.builder()
                .id(501L)
                .auctionWindowId(301L)
                .campaignId(201L)
                .bidderUserId(42L)
                .authorizedAmount(500L)
                .status("ACTIVE")
                .build();
    }

    @Test
    void authorizationBecomesReadyInOneOrderedLifecycle() {
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setLastDecisionVersion(7L);
        when(checkpointMapper.findByAuctionWindowId(301L)).thenReturn(checkpoint);
        ReconciliationTask task = ReconciliationTask.builder().id(901L).build();
        PromotionBidEscrowTransactionService.Authorization authorization =
                new PromotionBidEscrowTransactionService.Authorization(campaign, window, escrow, task);

        lifecycle.makeAuthorizationReady(authorization, now);

        ArgumentCaptor<PromotionBidRoute> routeCaptor = ArgumentCaptor.forClass(PromotionBidRoute.class);
        InOrder order = inOrder(hotStateRepository, routeRepository, reconciliationTaskMapper);
        order.verify(hotStateRepository).initialize(routeCaptor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        PromotionBidRoute route = routeCaptor.getValue();
        order.verify(hotStateRepository).projectAuthorization(route);
        order.verify(routeRepository).save(route, now);
        order.verify(reconciliationTaskMapper).markSucceeded(901L, 0L);
        assertThat(route.campaignId()).isEqualTo(201L);
        assertThat(route.authorizedAmount()).isEqualTo(500L);
    }

    @Test
    void recoveryUsesSameProjectionRecipeWithoutOwningTaskCompletion() {
        lifecycle.restoreAuthorization(campaign, window, escrow, now);

        ArgumentCaptor<PromotionBidRoute> routeCaptor = ArgumentCaptor.forClass(PromotionBidRoute.class);
        InOrder order = inOrder(hotStateRepository, routeRepository);
        order.verify(hotStateRepository).initialize(routeCaptor.capture(), org.mockito.ArgumentMatchers.eq(0L));
        PromotionBidRoute route = routeCaptor.getValue();
        order.verify(hotStateRepository).projectAuthorization(route);
        order.verify(routeRepository).save(route, now);
        verify(reconciliationTaskMapper, never()).markSucceeded(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
