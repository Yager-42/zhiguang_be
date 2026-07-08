package com.tongji.counter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.service.impl.CounterServiceImpl;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterServiceImplSingleFlightTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void missingSdsUsesDistributedSingleFlightInsteadOfRebuildLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CounterEventProducer eventProducer = mock(CounterEventProducer.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RedissonClient redisson = mock(RedissonClient.class);
        DistributedSingleFlightService singleFlightService = mock(DistributedSingleFlightService.class);
        RBucket<Long> backoffBucket = mock(RBucket.class);
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("like", 7L);
        expected.put("fav", 2L);

        when(redis.execute(any(RedisCallback.class))).thenReturn(null);
        doReturn(expected).when(singleFlightService).execute(
                eq("counter-sds"),
                eq("knowpost:1:fav,like"),
                any(TypeReference.class),
                any(Supplier.class)
        );

        CounterServiceImpl service = new CounterServiceImpl(redis, eventProducer, eventPublisher, redisson, singleFlightService);

        Map<String, Long> result = service.getCounts("knowpost", "1", List.of("like", "fav"));

        assertThat(result).isEqualTo(expected);
        verify(singleFlightService).execute(
                eq("counter-sds"),
                eq("knowpost:1:fav,like"),
                any(TypeReference.class),
                any(Supplier.class)
        );
        verify(redisson, never()).getLock("lock:sds-rebuild:knowpost:1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rateLimitedOwnerEntersSingleFlightAndReturnsZeroWithoutSuccessReplay() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CounterEventProducer eventProducer = mock(CounterEventProducer.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RedissonClient redisson = mock(RedissonClient.class);
        DistributedSingleFlightService singleFlightService = mock(DistributedSingleFlightService.class);
        RBucket<Long> backoffBucket = mock(RBucket.class);
        RBucket<Integer> expBucket = mock(RBucket.class);
        RBucket<Long> untilBucket = mock(RBucket.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);

        when(redis.execute(any(RedisCallback.class))).thenReturn(null);
        doReturn(backoffBucket).when(redisson).getBucket("backoff:sds-rebuild:until:knowpost:2");
        when(backoffBucket.get()).thenReturn(null);
        when(redisson.getRateLimiter("rl:sds-rebuild:knowpost:2")).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(eq(RateType.OVERALL), eq(3L), any(Duration.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(1)).thenReturn(false);
        doReturn(expBucket).when(redisson).getBucket("backoff:sds-rebuild:exp:knowpost:2");
        doReturn(untilBucket).when(redisson).getBucket("backoff:sds-rebuild:until:knowpost:2");
        when(expBucket.get()).thenReturn(null);
        when(singleFlightService.execute(
                eq("counter-sds"),
                eq("knowpost:2:fav,like"),
                any(TypeReference.class),
                any(Supplier.class)
        )).thenAnswer(invocation -> invocation.getArgument(3, Supplier.class).get());

        CounterServiceImpl service = new CounterServiceImpl(redis, eventProducer, eventPublisher, redisson, singleFlightService);

        Map<String, Long> result = service.getCounts("knowpost", "2", List.of("like", "fav"));

        assertThat(result).containsEntry("like", 0L).containsEntry("fav", 0L);
        verify(singleFlightService).execute(
                eq("counter-sds"),
                eq("knowpost:2:fav,like"),
                any(TypeReference.class),
                any(Supplier.class)
        );
        verify(expBucket).set(0);
        verify(untilBucket).set(any(Long.class), any(Duration.class));
    }
}
