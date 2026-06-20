package com.tongji.promotion.schedule;

import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 窗口关闭器：扫描已到期的 OPEN 窗口，逐个 GSP 结算并刷新对应资源类型的 active allocation 缓存。
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
}
