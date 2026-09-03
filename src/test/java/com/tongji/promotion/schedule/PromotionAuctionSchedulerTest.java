package com.tongji.promotion.schedule;

import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAuctionWindowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionSchedulerTest {

    @Mock
    private PromotionAllocationRefresher allocationRefresher;

    @Mock
    private PromotionAuctionWindowService windowService;

    private PromotionAuctionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PromotionAuctionScheduler(windowService, allocationRefresher);
    }

    @Test
    void ensuresFeedAndSearchWindowsExist() {
        scheduler.ensureWindows();

        verify(windowService).ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);
        verify(windowService).ensureOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT);
    }

    @Test
    void refreshAllocationsDelegatesToRefresher() {
        scheduler.refreshAllocations();

        verify(allocationRefresher).refreshCurrentAllocations(any(Instant.class));
    }
}
