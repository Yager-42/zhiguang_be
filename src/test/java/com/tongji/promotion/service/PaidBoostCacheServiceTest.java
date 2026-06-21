package com.tongji.promotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.service.PaidBoostCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidBoostCacheServiceTest {

    @Mock
    private PaidBoostCampaignMapper campaignMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PaidBoostCacheService cacheService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaidBoostProperties properties = new PaidBoostProperties();

    @BeforeEach
    void setUp() {
        cacheService = new PaidBoostCacheService(campaignMapper, redisTemplate, objectMapper, properties);
    }

    @Test
    void getActiveFallsBackToDbAndCaches() {
        Instant now = Instant.parse("2026-06-21T10:30:00Z");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("promotion:boost:active:home_recommendation")).thenReturn(null);
        when(campaignMapper.listActiveByChannel(PaidBoostChannel.HOME_RECOMMENDATION, now))
                .thenReturn(List.of(campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 100L, 0L)));

        List<PaidBoostCampaign> active = cacheService.getActive(PaidBoostChannel.HOME_RECOMMENDATION, now);

        assertThat(active).extracting(PaidBoostCampaign::getId).containsExactly(1L);
        verify(valueOperations).set(eq("promotion:boost:active:home_recommendation"), anyString(), any(Duration.class));
    }

    @Test
    void getActiveReadsFromCacheAndRefiltersExpiredOrExhausted() throws Exception {
        Instant now = Instant.parse("2026-06-21T10:30:00Z");
        // 缓存里混入：已过期（end < now）、预算耗尽、正常 active 各一条
        PaidBoostCampaign expired = PaidBoostCampaign.builder()
                .id(2L).creatorUserId(42L).postId(1002L).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .bidAmount(30L).boostValue(30L).unitPrice(2L).budgetTotal(100L).budgetConsumed(0L)
                .reserveBusinessRef("paid-boost:2:reserve").status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z")).endAt(Instant.parse("2026-06-21T10:20:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z")).updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
        PaidBoostCampaign exhausted = campaign(3L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 100L, 100L);
        PaidBoostCampaign live = campaign(4L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 100L, 0L);
        String json = objectMapper.writeValueAsString(List.of(expired, exhausted, live));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("promotion:boost:active:home_recommendation")).thenReturn(json);

        List<PaidBoostCampaign> active = cacheService.getActive(PaidBoostChannel.HOME_RECOMMENDATION, now);

        assertThat(active).extracting(PaidBoostCampaign::getId).containsExactly(4L);
        verify(campaignMapper, never()).listActiveByChannel(any(), any());
    }

    private PaidBoostCampaign campaign(long id, PaidBoostChannel channel, long boost, long budgetTotal, long consumed) {
        return PaidBoostCampaign.builder()
                .id(id).creatorUserId(42L).postId(1000L + id).channel(channel)
                .bidAmount(boost).boostValue(boost).unitPrice(2L).budgetTotal(budgetTotal).budgetConsumed(consumed)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z"))
                .endAt(Instant.parse("2026-06-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
    }
}
