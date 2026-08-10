package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionDecisionPath;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowCloserTest {

    @Mock private PromotionAuctionWindowMapper windowMapper;
    @Mock private PromotionBidMapper bidMapper;
    @Mock private PromotionAuctionService auctionService;
    @Mock private PromotionAllocationCacheService cacheService;
    @Mock private PromotionRedisWindowCloser redisWindowCloser;

    private PromotionAuctionWindowCloser closer;

    @BeforeEach
    void setUp() {
        closer = new PromotionAuctionWindowCloser(
                windowMapper, bidMapper, auctionService, cacheService, redisWindowCloser);
    }

    @Test
    void redisStreamWindowClosesOnlyThroughLua() {
        PromotionAuctionWindow window = window(PromotionDecisionPath.REDIS_STREAM);
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(redisWindowCloser).close(301L);
        verify(auctionService, never()).settleWindow(any(), anyList(), any());
    }

    @Test
    void legacyWindowDrainsWithoutCallingRedisAuthority() {
        PromotionAuctionWindow window = window(PromotionDecisionPath.LEGACY_BROKER);
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(bidMapper.listActiveBidsByWindowId(eq(301L), any(), any())).thenReturn(List.of());

        Instant now = Instant.parse("2026-06-20T11:00:00Z");
        closer.closeDueWindows(now, 50);

        verify(auctionService).settleWindow(eq(window), anyList(), eq(now));
        verify(redisWindowCloser, never()).close(301L);
    }

    @Test
    void oneRedisFailureDoesNotBlockOtherDueWindows() {
        PromotionAuctionWindow redisWindow = window(301L, PromotionDecisionPath.REDIS_STREAM);
        PromotionAuctionWindow legacyWindow = window(302L, PromotionDecisionPath.LEGACY_BROKER);
        when(windowMapper.listClosableWindows(any(), eq(50)))
                .thenReturn(List.of(redisWindow, legacyWindow));
        doThrow(new IllegalStateException("Redis unavailable")).when(redisWindowCloser).close(301L);
        when(bidMapper.listActiveBidsByWindowId(eq(302L), any(), any())).thenReturn(List.of());

        Instant now = Instant.parse("2026-06-20T11:00:00Z");
        closer.closeDueWindows(now, 50);

        verify(redisWindowCloser).close(301L);
        verify(auctionService).settleWindow(eq(legacyWindow), anyList(), eq(now));
    }

    private PromotionAuctionWindow window(PromotionDecisionPath decisionPath) {
        return window(301L, decisionPath);
    }

    private PromotionAuctionWindow window(long id, PromotionDecisionPath decisionPath) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(1L)
                .decisionPath(decisionPath)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse("2026-06-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:00:00Z"))
                .build();
    }
}
