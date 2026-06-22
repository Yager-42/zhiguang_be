package com.tongji.promotion.schedule;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowCloserTest {

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    @Mock
    private PromotionBidMapper bidMapper;

    @Mock
    private PromotionAuctionService auctionService;

    @Mock
    private PromotionDecisionLogPort decisionLogPort;

    @Mock
    private IdService idService;

    @Mock
    private PromotionAllocationCacheService cacheService;

    private PromotionAuctionWindowCloser closer;
    private PromotionBPrimeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        closer = new PromotionAuctionWindowCloser(windowMapper, bidMapper, auctionService, decisionLogPort,
                cacheService, idService, properties);
    }

    @Test
    void closesDueWindowsAndRefreshesCache() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(9001L);

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        org.mockito.ArgumentCaptor<PromotionAuctionDecision> captor = forClass(PromotionAuctionDecision.class);
        verify(decisionLogPort).append(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().decisionType()).isEqualTo("WINDOW_CLOSED");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().auctionWindowId()).isEqualTo(301L);
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(windowMapper, never()).markSettled(eq(301L), any());
    }

    @Test
    void closesDueWindowsDirectlyWhenBprimeDisabled() {
        properties.setEnabled(false);
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(auctionService).settleWindow(eq(window), anyList(), eq(Instant.parse("2026-06-20T11:00:00Z")));
        verify(decisionLogPort, never()).append(any());
    }

    @Test
    void refreshCurrentAllocationsRefreshesBothResources() {
        closer.refreshCurrentAllocations(Instant.parse("2026-06-20T11:00:00Z"));

        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
    }

    private PromotionAuctionWindow window(long id, PromotionResourceType type, String start, String end) {
        return PromotionAuctionWindow.builder()
                .id(id).resourceType(type)
                .windowStartAt(Instant.parse(start)).windowEndAt(Instant.parse(end))
                .slotCount(1).reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse(start)).updatedAt(Instant.parse(start))
                .build();
    }

    private PromotionBid bid(long id, long campaignId, long bidderUserId, long bidAmount) {
        return PromotionBid.builder()
                .id(id).campaignId(campaignId).auctionWindowId(301L).bidderUserId(bidderUserId)
                .bidAmount(bidAmount).walletBusinessRef("promotion-bid:" + id)
                .status(PromotionBidStatus.ACTIVE)
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build();
    }
}
