package com.tongji.promotion.schedule;

import com.tongji.promotion.service.PaidBoostCacheService;
import com.tongji.promotion.service.PaidBoostSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaidBoostSchedulerTest {

    @Mock
    private PaidBoostSettlementService settlementService;

    @Mock
    private PaidBoostCacheService cacheService;

    private PaidBoostScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PaidBoostScheduler(settlementService, cacheService);
    }

    @Test
    void settlePendingSettlesClosesAndRefreshesCache() {
        scheduler.settlePending();

        verify(settlementService).settlePendingDeliveries(org.mockito.ArgumentMatchers.any(), eq(100));
        verify(settlementService).closeExpiredCampaigns(org.mockito.ArgumentMatchers.any(), anyInt());
        verify(cacheService).refreshAll(org.mockito.ArgumentMatchers.any());
    }
}
