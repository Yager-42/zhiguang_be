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

@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionAllocationCacheService cacheService;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            Instant allocationStartAt = window.getWindowEndAt();
            long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
            Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
            auctionService.settleWindow(window,
                    bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt),
                    now);
            cacheService.refreshActiveAllocations(window.getResourceType(), now);
        }
    }

    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
