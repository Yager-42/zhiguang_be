package com.tongji.promotion.schedule;

import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 窗口关闭器：扫描已到期的 OPEN 窗口，逐个 GSP 结算并刷新对应资源类型的 active allocation 缓存。
 * 另提供 {@link #refreshCurrentAllocations}：不依赖是否有窗口被结算，周期性从 DB 重刷两类资源的当前 allocation 缓存，
 * 保证即使某轮结算/刷新失败，新窗口的 allocation 也能在下一轮刷新周期内上线（最坏滞后一个调度周期）。
 */
@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionAllocationCacheService cacheService;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            auctionService.settleWindow(window, bidMapper.listActiveBidsByWindowId(window.getId()), now);
            cacheService.refreshActiveAllocations(window.getResourceType(), now);
        }
    }

    /** 无条件重刷两类资源当前的 active allocation 缓存（结算驱动之外的兜底刷新）。 */
    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
