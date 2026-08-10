package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionDecisionPath;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 按窗口固定的 decisionPath 关窗，禁止同一窗口双重裁决。
 */
@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionAuctionWindowCloser.class);

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionAllocationCacheService cacheService;
    private final PromotionRedisWindowCloser redisWindowCloser;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            try {
                if (window.getDecisionPath() == PromotionDecisionPath.REDIS_STREAM) {
                    redisWindowCloser.close(window.getId());
                    continue;
                }
                settleLegacyWindow(window, now);
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to close promotion auction window, auctionWindowId={}, decisionPath={}",
                        window.getId(), window.getDecisionPath(), exception);
            }
        }
    }

    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }

    private void settleLegacyWindow(PromotionAuctionWindow window, Instant settledAt) {
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        auctionService.settleWindow(window,
                bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt),
                settledAt);
        cacheService.refreshActiveAllocations(window.getResourceType(), settledAt);
    }
}
