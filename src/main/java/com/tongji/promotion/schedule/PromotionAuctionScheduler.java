package com.tongji.promotion.schedule;

import com.tongji.promotion.config.PromotionProperties;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAuctionWindowService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 推广位竞价调度：每轮保证两类资源的当前/下一窗口存在，并结算到期窗口。
 * 首期直接用 {@code @Scheduled}；失败重试/可观测如需，再升级到 reconciliation_task。
 */
@Component
@RequiredArgsConstructor
public class PromotionAuctionScheduler {

    private final PromotionAuctionWindowService windowService;
    private final PromotionAuctionWindowCloser closer;
    private final PromotionProperties properties;

    @Scheduled(fixedDelayString = "${promotion.slot-auction.close-window-delay-ms:30000}")
    public void ensureWindows() {
        windowService.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);
        windowService.ensureOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT);
    }

    @Scheduled(fixedDelayString = "${promotion.slot-auction.close-window-delay-ms:30000}")
    public void closeDueWindows() {
        closer.closeDueWindows(Instant.now(), properties.getSettleBatchSize());
    }

    /**
     * 周期性重刷当前 allocation 缓存：与是否有窗口被结算解耦，
     * 保证新窗口的 allocation 最坏在一个调度周期内上线（读路径的时间过滤仍负责丢弃过期 allocation）。
     */
    @Scheduled(fixedDelayString = "${promotion.slot-auction.close-window-delay-ms:30000}")
    public void refreshAllocations() {
        closer.refreshCurrentAllocations(Instant.now());
    }
}
