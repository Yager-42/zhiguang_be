package com.tongji.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.config.PromotionProperties;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 位分配 Redis 缓存集成测试：真 Redis 读写 + 真 Jackson 序列化（含 Instant）+ 读路径时间过滤。
 * <p>风格对齐 {@link PromotionMysqlIntegrationTest}；无 Redis 时通过 {@code @EnabledIf} 跳过。</p>
 */
@SpringBootTest(classes = PromotionAllocationRedisIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.database=0"
})
@EnabledIf("redisReachable")
class PromotionAllocationRedisIntegrationTest {

    private static final String FEED_CACHE_KEY = "promotion:allocation:active:feed_top_slot";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PromotionAllocationCacheService cacheService;

    @Autowired
    private PromotionAllocationService allocationService;

    @Autowired
    private PromotionSlotAllocationMapper allocationMapper;

    @BeforeEach
    void cleanUp() {
        redis.delete(FEED_CACHE_KEY);
        reset(allocationMapper);
    }

    @Test
    void refreshThenReadRoundTripsAllocationThroughRealRedis() {
        Instant now = Instant.now();
        PromotionSlotAllocation active = activeAllocation(201L, now.minusSeconds(3600), now.plusSeconds(3600));
        when(allocationMapper.listActive(eq(PromotionResourceType.FEED_TOP_SLOT), any())).thenReturn(List.of(active));

        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);

        // 真 Redis 读回 + 真 Jackson 反序列化（含 Instant 字段、record 规范构造）
        List<PromotionAllocationView> fromCache = cacheService.readFromCache(PromotionResourceType.FEED_TOP_SLOT);
        assertThat(fromCache).hasSize(1);
        assertThat(fromCache.get(0).postId()).isEqualTo("201");
        assertThat(fromCache.get(0).placementType()).isEqualTo("feed_top_slot");
        assertThat(fromCache.get(0).allocationStartAt()).isEqualTo(active.getAllocationStartAt());
        assertThat(fromCache.get(0).allocationEndAt()).isEqualTo(active.getAllocationEndAt());

        // getActive 走缓存命中，时间过滤保留当前有效 allocation
        assertThat(allocationService.getActiveFeedAllocation())
                .extracting(PromotionAllocationView::postId).contains("201");
    }

    @Test
    void getActiveDoesNotLeakStaleNorEmptyWindowWhenRealCacheHoldsOnlyStale() {
        Instant now = Instant.now();
        // 真实缓存里只写过期 allocation（窗口切走、closer 还没刷新）
        PromotionSlotAllocation stale = activeAllocation(202L, now.minusSeconds(7200), now.minusSeconds(3600));
        when(allocationMapper.listActive(eq(PromotionResourceType.FEED_TOP_SLOT), any())).thenReturn(List.of(stale));
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        assertThat(cacheService.readFromCache(PromotionResourceType.FEED_TOP_SLOT)).hasSize(1);

        // DB 当前已有新窗口 active（真实 listActive 按时间过滤，只返回 active）
        reset(allocationMapper);
        PromotionSlotAllocation dbActive = activeAllocation(203L, now.minusSeconds(3600), now.plusSeconds(3600));
        when(allocationMapper.listActive(eq(PromotionResourceType.FEED_TOP_SLOT), any())).thenReturn(List.of(dbActive));

        List<PromotionAllocationView> result = allocationService.getActiveFeedAllocation();
        // 不泄漏 stale（202）、不空窗：回源 DB 拿到当前 active（203）
        assertThat(result).extracting(PromotionAllocationView::postId).containsExactly("203");
    }

    @Test
    void getActiveFallsBackToDbWhenCacheMisses() {
        // 缓存缺失（key 不存在）时回源 DB
        Instant now = Instant.now();
        PromotionSlotAllocation active = activeAllocation(203L, now.minusSeconds(3600), now.plusSeconds(3600));
        when(allocationMapper.listActive(eq(PromotionResourceType.FEED_TOP_SLOT), any())).thenReturn(List.of(active));

        List<PromotionAllocationView> result = allocationService.getActiveFeedAllocation();

        assertThat(result).extracting(PromotionAllocationView::postId).contains("203");
    }

    private PromotionSlotAllocation activeAllocation(long postId, Instant start, Instant end) {
        long n = postId * 31;
        return PromotionSlotAllocation.builder()
                .id(n).auctionWindowId(n + 1).resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .slotIndex(0).campaignId(n + 2).postId(postId).bidderUserId(42L).clearingPrice(80L)
                .allocationStartAt(start).allocationEndAt(end).createdAt(start).build();
    }

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @ImportAutoConfiguration({RedisAutoConfiguration.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PromotionProperties promotionProperties() {
            return new PromotionProperties();
        }

        @Bean
        PromotionSlotAllocationMapper promotionSlotAllocationMapper() {
            return mock(PromotionSlotAllocationMapper.class);
        }

        @Bean
        PromotionAllocationCacheService promotionAllocationCacheService(StringRedisTemplate redis,
                                                                        PromotionSlotAllocationMapper mapper,
                                                                        ObjectMapper objectMapper,
                                                                        PromotionProperties properties) {
            return new PromotionAllocationCacheService(redis, mapper, objectMapper, properties);
        }

        @Bean
        PromotionAllocationService promotionAllocationService(PromotionAllocationCacheService cacheService,
                                                              PromotionSlotAllocationMapper mapper) {
            return new PromotionAllocationService(cacheService, mapper);
        }
    }
}
