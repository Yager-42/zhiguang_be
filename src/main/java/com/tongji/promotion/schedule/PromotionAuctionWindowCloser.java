package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionAuctionWindowCloser.class);

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionAllocationCacheService cacheService;
    private final PromotionRedisWindowCloser redisWindowCloser;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            try {
                redisWindowCloser.close(window.getId());
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to close promotion auction window, auctionWindowId={}",
                        window.getId(), exception);
            }
        }
    }

    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
