package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowCloserTest {

    @Mock private PromotionAuctionWindowMapper windowMapper;
    @Mock private PromotionAllocationCacheService cacheService;
    @Mock private PromotionRedisWindowCloser redisWindowCloser;

    private PromotionAuctionWindowCloser closer;

    @BeforeEach
    void setUp() {
        closer = new PromotionAuctionWindowCloser(windowMapper, cacheService, redisWindowCloser);
    }

    @Test
    void everyDueWindowClosesOnlyThroughRedisAuthority() {
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window(301L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(redisWindowCloser).close(301L);
    }

    @Test
    void oneRedisFailureDoesNotBlockOtherDueWindows() {
        when(windowMapper.listClosableWindows(any(), eq(50)))
                .thenReturn(List.of(window(301L), window(302L)));
        doThrow(new IllegalStateException("Redis unavailable")).when(redisWindowCloser).close(301L);

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(redisWindowCloser).close(301L);
        verify(redisWindowCloser).close(302L);
    }

    private PromotionAuctionWindow window(long id) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse("2026-06-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:00:00Z"))
                .build();
    }
}
