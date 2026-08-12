package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionBidRouteRepositoryTest {

    private static final long CAMPAIGN_ID = 201L;
    private static final String ROUTE_KEY = "promotion:bprime:route:201";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private PromotionBidRouteRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new PromotionBidRouteRepository(
                redisTemplate, objectMapper, new PromotionBPrimeProperties());
    }

    @Test
    void savedRouteIsServedWithoutAnotherRedisRead() {
        Instant now = Instant.parse("2026-08-09T10:00:00Z");
        PromotionBidRoute route = route(now.plus(Duration.ofHours(1)));

        repository.save(route, now);
        PromotionBidRoute loaded = repository.find(CAMPAIGN_ID);

        assertThat(loaded).isEqualTo(route);
        verify(valueOperations).set(eq(ROUTE_KEY), anyString(), eq(Duration.ofMinutes(70)));
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void redisRouteIsDeserializedOnlyOnFirstCacheMiss() throws Exception {
        PromotionBidRoute route = route(Instant.parse("2026-08-09T11:00:00Z"));
        when(valueOperations.get(ROUTE_KEY)).thenReturn(objectMapper.writeValueAsString(route));

        assertThat(repository.find(CAMPAIGN_ID)).isEqualTo(route);
        assertThat(repository.find(CAMPAIGN_ID)).isEqualTo(route);

        verify(valueOperations, times(1)).get(ROUTE_KEY);
    }

    private PromotionBidRoute route(Instant windowEndAt) {
        return new PromotionBidRoute(CAMPAIGN_ID, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L, "OPEN", windowEndAt, 1);
    }
}
