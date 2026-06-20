package com.tongji.promotion.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.config.PromotionProperties;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 位分配 Redis 缓存：独立于 feed 页面缓存，避免窗口切换与页面失效策略耦合。
 * <p>窗口结算后由 closer 调 {@link #refreshActiveAllocations}；读路径经 {@link #readFromCache} 取缓存值。</p>
 */
@Service
@RequiredArgsConstructor
public class PromotionAllocationCacheService {

    private static final String CACHE_KEY_PREFIX = "promotion:allocation:active:";

    private final StringRedisTemplate redis;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final ObjectMapper objectMapper;
    private final PromotionProperties properties;

    /** 刷新某资源类型的 active allocation 缓存（窗口结算后调用）。 */
    public void refreshActiveAllocations(PromotionResourceType resourceType, Instant now) {
        List<PromotionAllocationView> active = allocationMapper.listActive(resourceType, now).stream()
                .map(PromotionAllocationView::from)
                .toList();
        try {
            redis.opsForValue().set(cacheKey(resourceType), objectMapper.writeValueAsString(active),
                    Duration.ofSeconds(properties.getCacheTtlSeconds()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to refresh promotion allocation cache", e);
        }
    }

    /** 读缓存；不存在或反序列化失败返回 null（交由调用方回源 DB）。 */
    public List<PromotionAllocationView> readFromCache(PromotionResourceType resourceType) {
        String json = redis.opsForValue().get(cacheKey(resourceType));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PromotionAllocationView>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private String cacheKey(PromotionResourceType resourceType) {
        return CACHE_KEY_PREFIX + resourceType.name().toLowerCase();
    }
}
