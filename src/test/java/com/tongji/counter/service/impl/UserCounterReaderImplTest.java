package com.tongji.counter.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.service.UserCounterService;
import com.tongji.counter.service.UserCounters;
import com.tongji.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCounterReaderImplTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private UserCounterService writer;
    private RelationMapper relationMapper;
    private DistributedSingleFlightService singleFlight;
    private UserCounterReaderImpl reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        writer = mock(UserCounterService.class);
        relationMapper = mock(RelationMapper.class);
        singleFlight = mock(DistributedSingleFlightService.class);
        when(redis.opsForValue()).thenReturn(values);
        reader = new UserCounterReaderImpl(redis, writer, relationMapper, singleFlight);
    }

    @Test
    void findDistinguishesMissingFromStoredZero() {
        when(redis.execute(any(RedisCallback.class))).thenReturn(null, new byte[20]);

        assertThat(reader.find(42L)).isEmpty();
        assertThat(reader.find(42L)).contains(UserCounters.zero());
        verify(singleFlight, never()).execute(any(), any(), any(TypeReference.class), any());
    }

    @Test
    void findDecodesFiveUnsignedBigEndianSegments() {
        byte[] raw = counters(11L, 22L, 33L, 0x8000_0000L, 0xFFFF_FFFFL);
        when(redis.execute(any(RedisCallback.class))).thenReturn(raw);

        Optional<UserCounters> result = reader.find(42L);

        assertThat(result).contains(new UserCounters(11L, 22L, 33L, 0x8000_0000L, 0xFFFF_FFFFL));
        verify(relationMapper, never()).countFollowingActive(any(Long.class));
    }

    @Test
    void getVerifiedReturnsCachedFactsWithoutSamplingLease() {
        byte[] raw = counters(11L, 22L, 33L, 44L, 55L);
        when(redis.execute(any(RedisCallback.class))).thenReturn(raw);
        when(values.setIfAbsent(eq("ucnt:chk:42"), eq("1"), any())).thenReturn(false);

        assertThat(reader.getVerified(42L)).isEqualTo(new UserCounters(11L, 22L, 33L, 44L, 55L));
        verify(relationMapper, never()).countFollowingActive(42L);
        verify(singleFlight, never()).execute(any(), any(), any(TypeReference.class), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getVerifiedRebuildsMissingFactsThroughSingleFlight() {
        byte[] rebuilt = counters(1L, 2L, 3L, 4L, 5L);
        when(redis.execute(any(RedisCallback.class))).thenReturn(null, rebuilt);
        doAnswer(invocation -> ((Supplier<UserCounters>) invocation.getArgument(3)).get())
                .when(singleFlight)
                .execute(eq("user-counter"), eq("42"), any(TypeReference.class), any(Supplier.class));

        assertThat(reader.getVerified(42L)).isEqualTo(new UserCounters(1L, 2L, 3L, 4L, 5L));
        verify(writer).rebuildAllCounters(42L);
        verify(singleFlight).execute(eq("user-counter"), eq("42"), any(TypeReference.class), any(Supplier.class));
    }

    @Test
    void getVerifiedReturnsCachedFactsWhenSampleMatches() {
        byte[] raw = counters(11L, 22L, 33L, 44L, 55L);
        when(redis.execute(any(RedisCallback.class))).thenReturn(raw);
        when(values.setIfAbsent(eq("ucnt:chk:42"), eq("1"), any())).thenReturn(true);
        when(relationMapper.countFollowingActive(42L)).thenReturn(11);
        when(relationMapper.countFollowerActive(42L)).thenReturn(22);

        assertThat(reader.getVerified(42L)).isEqualTo(new UserCounters(11L, 22L, 33L, 44L, 55L));
        verify(writer, never()).rebuildAllCounters(42L);
    }

    private static byte[] counters(long followings, long followers, long posts, long liked, long faved) {
        byte[] raw = new byte[20];
        write32be(raw, 0, followings);
        write32be(raw, 4, followers);
        write32be(raw, 8, posts);
        write32be(raw, 12, liked);
        write32be(raw, 16, faved);
        return raw;
    }

    private static void write32be(byte[] raw, int offset, long value) {
        raw[offset] = (byte) ((value >>> 24) & 0xFF);
        raw[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        raw[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        raw[offset + 3] = (byte) (value & 0xFF);
    }
}
