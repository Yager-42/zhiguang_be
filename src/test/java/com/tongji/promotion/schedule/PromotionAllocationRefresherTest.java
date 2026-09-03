package com.tongji.promotion.schedule;

import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionAllocationRefresherTest {

    @Test
    void refreshesFeedAndSearchAllocationsAtTheSameInstant() {
        PromotionAllocationCacheService cacheService = mock(PromotionAllocationCacheService.class);
        PromotionAllocationRefresher refresher = new PromotionAllocationRefresher(cacheService);
        Instant now = Instant.parse("2026-06-20T11:00:00Z");

        refresher.refreshCurrentAllocations(now);

        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
