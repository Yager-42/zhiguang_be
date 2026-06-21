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
 * <p>请求路径永不触发拍卖，只消费已结算的 active allocation。
 * 即使 closer 还没刷新缓存或 refresh 失败，也按当前时间过滤掉已越过 {@code allocationEndAt}
 * 的过期 allocation，避免窗口切换后继续吐旧商业位。</p>
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
        Instant now = Instant.now();
        List<PromotionAllocationView> cached = cacheService.readFromCache(resourceType);
        if (cached != null) {
            List<PromotionAllocationView> active = cached.stream()
                    .filter(view -> view.isActiveAt(now))
                    .toList();
            // ponytail: 缓存非空但过滤后全不可用（窗口切走的 stale / 旧格式无时间字段）→ 回源 DB 取当前 active，
            // 避免 closer 还没刷新时新窗口 allocation 空窗。raw 缓存为空表示上次刷新本就无 active
            // （结算总会触发刷新），不回源，避免无 promotion 时每次请求打 DB。
            if (active.isEmpty() && !cached.isEmpty()) {
                return loadFromDb(resourceType, now);
            }
            return active;
        }
        return loadFromDb(resourceType, now);
    }

    private List<PromotionAllocationView> loadFromDb(PromotionResourceType resourceType, Instant now) {
        return allocationMapper.listActive(resourceType, now).stream()
                .map(PromotionAllocationView::from)
                .toList();
    }
}
