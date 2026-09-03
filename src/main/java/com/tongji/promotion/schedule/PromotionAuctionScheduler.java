package com.tongji.promotion.schedule;

import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAuctionWindowService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 周期性保证推广竞价窗口存在，并刷新当前 allocation 缓存。
 */
@Component
@RequiredArgsConstructor
public class PromotionAuctionScheduler {

    private final PromotionAuctionWindowService windowService;
    private final PromotionAllocationRefresher allocationRefresher;

    @Scheduled(fixedDelayString = "${promotion.slot-auction.maintenance-delay-ms:30000}")
    public void ensureWindows() {
        windowService.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);
        windowService.ensureOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT);
    }

    /**
     * 周期性重刷当前 allocation 缓存：与窗口到期裁决解耦，
     * 保证新窗口的 allocation 最坏在一个调度周期内上线（读路径的时间过滤仍负责丢弃过期 allocation）。
     */
    @Scheduled(fixedDelayString = "${promotion.slot-auction.maintenance-delay-ms:30000}")
    public void refreshAllocations() {
        allocationRefresher.refreshCurrentAllocations(Instant.now());
    }
}
