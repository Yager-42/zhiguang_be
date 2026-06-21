package com.tongji.promotion.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostChannel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * active boost 读路径：统一由本 service 提供，独立 Redis key（与 slot allocation 缓存分离）。
 * <p>读路径先查缓存（命中则按 now 重新过滤窗口与预算），未命中回源 DB 并回写；
 * {@link #refreshAll} 供定时调度在结算/关闭后刷新，避免过期活动残留。</p>
 */
@Service
public class PaidBoostCacheService {

    private static final String CACHE_KEY_PREFIX = "promotion:boost:active:";

    private final PaidBoostCampaignMapper campaignMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PaidBoostProperties properties;

    public PaidBoostCacheService(PaidBoostCampaignMapper campaignMapper,
                                 StringRedisTemplate redis,
                                 ObjectMapper objectMapper,
                                 PaidBoostProperties properties) {
        this.campaignMapper = campaignMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<PaidBoostCampaign> getActive(PaidBoostChannel channel, Instant now) {
        String key = cacheKey(channel);
        List<PaidBoostCampaign> cached = read(key);
        if (cached != null) {
            return filterActive(cached, now);
        }
        List<PaidBoostCampaign> loaded = campaignMapper.listActiveByChannel(channel, now);
        write(key, loaded);
        return loaded;
    }

    /** 刷新某渠道 active 缓存（结算/关闭后调用）。 */
    public void refresh(PaidBoostChannel channel, Instant now) {
        write(cacheKey(channel), campaignMapper.listActiveByChannel(channel, now));
    }

    /** 刷新所有渠道 active 缓存。 */
    public void refreshAll(Instant now) {
        for (PaidBoostChannel channel : PaidBoostChannel.values()) {
            refresh(channel, now);
        }
    }

    private List<PaidBoostCampaign> filterActive(List<PaidBoostCampaign> cached, Instant now) {
        return cached.stream()
                .filter(c -> !now.isBefore(c.getStartAt()) && now.isBefore(c.getEndAt()))
                .filter(c -> c.getBudgetConsumed() < c.getBudgetTotal())
                .toList();
    }

    private List<PaidBoostCampaign> read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PaidBoostCampaign>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private void write(String key, List<PaidBoostCampaign> campaigns) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(campaigns),
                    Duration.ofSeconds(properties.getActiveCacheTtlSeconds()));
        } catch (Exception ignored) {
            // 缓存写失败不影响读路径正确性，交由下次回源
        }
    }

    private String cacheKey(PaidBoostChannel channel) {
        return CACHE_KEY_PREFIX + channel.wireValue();
    }
}
