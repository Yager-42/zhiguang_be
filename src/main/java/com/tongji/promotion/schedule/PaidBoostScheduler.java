package com.tongji.promotion.schedule;

import com.tongji.promotion.service.PaidBoostCacheService;
import com.tongji.promotion.service.PaidBoostSettlementService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 付费加权定时结算：聚合 PENDING delivery 扣费、关闭到期活动释放剩余预算、刷新 active boost 缓存。
 * <p>沿用 slot auction 的 {@code @Scheduled} 风格，调度间隔由 {@code promotion.paid-boost.settle-delay-ms} 配置。</p>
 */
@Component
public class PaidBoostScheduler {

    private static final int BATCH_SIZE = 100;

    private final PaidBoostSettlementService settlementService;
    private final PaidBoostCacheService cacheService;

    public PaidBoostScheduler(PaidBoostSettlementService settlementService, PaidBoostCacheService cacheService) {
        this.settlementService = settlementService;
        this.cacheService = cacheService;
    }

    @Scheduled(fixedDelayString = "${promotion.paid-boost.settle-delay-ms:30000}")
    public void settlePending() {
        Instant now = Instant.now();
        settlementService.settlePendingDeliveries(now, BATCH_SIZE);
        settlementService.closeExpiredCampaigns(now, BATCH_SIZE);
        cacheService.refreshAll(now);
    }
}
