package com.tongji.promotion.service;

import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 位分配读路径：feed/search 商业位插入的唯一来源。优先读 Redis 缓存，缓存缺失直接回源 DB。
 * <p>请求路径永不触发拍卖，只消费已结算的 active allocation。</p>
 */
@Service
@RequiredArgsConstructor
public class PromotionAllocationService {

    private final PromotionAllocationCacheService cacheService;
    private final PromotionSlotAllocationMapper allocationMapper;

    public List<PromotionAllocationView> getActiveFeedAllocation() {
        return getActive(PromotionResourceType.FEED_TOP_SLOT);
    }

    public List<PromotionAllocationView> getActiveSearchAllocation() {
        return getActive(PromotionResourceType.SEARCH_TOP_SLOT);
    }

    public List<PromotionAllocationView> getActive(PromotionResourceType resourceType) {
        List<PromotionAllocationView> cached = cacheService.readFromCache(resourceType);
        if (cached != null) {
            return cached;
        }
        return allocationMapper.listActive(resourceType, Instant.now()).stream()
                .map(PromotionAllocationView::from)
                .toList();
    }
}
