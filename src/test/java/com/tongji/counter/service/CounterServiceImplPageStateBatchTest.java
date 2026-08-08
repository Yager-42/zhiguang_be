package com.tongji.counter.service;

import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.service.impl.CounterServiceImpl;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterServiceImplPageStateBatchTest {

    @Test
    void readsAllCountsAndLikedBitsWithOneSharedConnectionCommand() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class)))
                .thenReturn(List.of("3", "5", "1", "7", "11", "0"));
        CounterServiceImpl service = new CounterServiceImpl(redis, mock(CounterEventProducer.class),
                mock(ApplicationEventPublisher.class), redisson,
                mock(DistributedSingleFlightService.class));

        var result = service.getPageStateBatch("comment", List.of("1", "2"), 7L,
                List.of("like", "comment"));

        assertThat(result.get("1").counts()).containsEntry("like", 3L).containsEntry("comment", 5L);
        assertThat(result.get("1").liked()).isTrue();
        assertThat(result.get("2").counts()).containsEntry("like", 7L).containsEntry("comment", 11L);
        assertThat(result.get("2").liked()).isFalse();
        verify(script, times(1)).eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class));
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        verify(redis, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void anonymousReadOmitsLikedResults() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class)))
                .thenReturn(List.of("1", "2"));
        CounterServiceImpl service = new CounterServiceImpl(redis, mock(CounterEventProducer.class),
                mock(ApplicationEventPublisher.class), redisson,
                mock(DistributedSingleFlightService.class));

        var result = service.getPageStateBatch("comment", List.of("1"), null, List.of("like", "comment"));

        assertThat(result.get("1").liked()).isFalse();
        verify(script, times(1)).eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class));
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        verify(redis, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void readsFeedCountsLikedAndFavedWithOneSharedConnectionCommand() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class)))
                .thenReturn(List.of("3", "5", "1", "0", "7", "11", "0", "1"));
        CounterServiceImpl service = new CounterServiceImpl(redis, mock(CounterEventProducer.class),
                mock(ApplicationEventPublisher.class), redisson,
                mock(DistributedSingleFlightService.class));

        var result = service.getFeedPageStateBatch("knowpost", List.of("1", "2"), 7L,
                List.of("like", "fav"));

        assertThat(result.get("1").counts()).containsEntry("like", 3L).containsEntry("fav", 5L);
        assertThat(result.get("1").liked()).isTrue();
        assertThat(result.get("1").faved()).isFalse();
        assertThat(result.get("2").counts()).containsEntry("like", 7L).containsEntry("fav", 11L);
        assertThat(result.get("2").liked()).isFalse();
        assertThat(result.get("2").faved()).isTrue();
        verify(script, times(1)).eval(any(RScript.Mode.class), any(String.class), any(RScript.ReturnType.class),
                anyList(), any(Object[].class));
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        verify(redis, never()).executePipelined(any(SessionCallback.class));
    }
}
