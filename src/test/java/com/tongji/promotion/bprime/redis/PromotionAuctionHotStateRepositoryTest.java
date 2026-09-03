package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionAuctionHotStateRepositoryTest {
    private interface StringObjectHashOperations extends HashOperations<String, Object, Object> {
    }

    private interface StringSetOperations extends SetOperations<String, String> {
    }

    @Test
    void initializesWindowKeysWithFixedDeadlineAndPriceRules() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn("OK");
        StringSetOperations setOperations = mock(StringSetOperations.class);
        when(setOperations.add(anyString(), any(String[].class))).thenReturn(1L);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        PromotionAuctionHotStateRepository repository =
                new PromotionAuctionHotStateRepository(redisTemplate, new PromotionBPrimeProperties());

        repository.initialize(new PromotionBidRoute(201L, 42L, 1001L, 301L, "FEED_TOP_SLOT", 100L, 500L,
                "OPEN", Instant.parse("2026-08-08T12:00:00Z"), 2), 17L);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(), eq(List.of(
                "promotion:auction:{301}:state",
                "promotion:auction:{301}:ranking",
                "promotion:auction:{301}:escrow",
                "promotion:auction:{301}:events")), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(
                String.valueOf(Instant.parse("2026-08-08T12:00:00Z").toEpochMilli()),
                "100", "2", "FEED_TOP_SLOT", "17", "86400", "100", "0");
    }

    @Test
    void missingOpenStateRejectsStartupWithoutRestoringActiveStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StringObjectHashOperations hashOperations = mock(StringObjectHashOperations.class);
        StringSetOperations setOperations = mock(StringSetOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        PromotionAuctionHotStateRepository repository =
                new PromotionAuctionHotStateRepository(redisTemplate, new PromotionBPrimeProperties());

        assertThatThrownBy(() -> repository.verifyAndRecoverActiveWindows(List.of(openWindow(301L))))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("missing");

        verify(setOperations, never()).add(anyString(), any(String[].class));
    }

    @Test
    void legacyExtendedDeadlineRejectsStartupWithoutRestoringActiveStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StringObjectHashOperations hashOperations = mock(StringObjectHashOperations.class);
        StringSetOperations setOperations = mock(StringSetOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(hashOperations.get(PromotionAuctionRedisKeys.state(301L), "windowEndAtEpochMs"))
                .thenReturn(String.valueOf(Instant.parse("2026-08-08T12:01:00Z").toEpochMilli()));
        PromotionAuctionHotStateRepository repository =
                new PromotionAuctionHotStateRepository(redisTemplate, new PromotionBPrimeProperties());

        assertThatThrownBy(() -> repository.verifyAndRecoverActiveWindows(List.of(openWindow(301L))))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("deadline mismatch");

        verify(setOperations, never()).add(anyString(), any(String[].class));
    }

    private PromotionAuctionWindow openWindow(long id) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-08-08T11:00:00Z"))
                .windowEndAt(Instant.parse("2026-08-08T12:00:00Z"))
                .slotCount(1)
                .reservePrice(100L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
    }
}
