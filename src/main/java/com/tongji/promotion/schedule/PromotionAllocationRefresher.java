package com.tongji.promotion.schedule;

import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 刷新当前推广位 allocation 缓存，不负责窗口关窗或结算。
 */
@Component
@RequiredArgsConstructor
public class PromotionAllocationRefresher {

    private final PromotionAllocationCacheService cacheService;

    /**
     * 按当前时间刷新所有推广资源位的有效 allocation。
     *
     * @param now 当前刷新时间，不允许为 {@code null}
     */
    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
