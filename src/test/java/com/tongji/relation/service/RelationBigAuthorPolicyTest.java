package com.tongji.relation.service;

import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounters;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.impl.RelationServiceImpl;
import com.tongji.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationBigAuthorPolicyTest {
    private RelationMapper relationMapper;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsets;
    private UserCounterReader userCounterReader;
    private RelationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        relationMapper = mock(RelationMapper.class);
        redis = mock(StringRedisTemplate.class);
        zsets = mock(ZSetOperations.class);
        userCounterReader = mock(UserCounterReader.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        service = new RelationServiceImpl(relationMapper, redis, mock(UserMapper.class), userCounterReader);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("toUserId", 7L);
        row.put("createdAt", Timestamp.from(Instant.parse("2026-06-18T10:00:00Z")));
        when(relationMapper.listFollowingRows(42L, 20, 0)).thenReturn(Map.of(7L, row));
        when(zsets.reverseRange("uf:flws:42", 0, 19)).thenReturn(Set.of(), Set.of("7"));
    }

    @Test
    void highPostCountDoesNotEnableLargeAuthorTopCache() {
        when(userCounterReader.find(42L))
                .thenReturn(Optional.of(new UserCounters(0L, 499_999L, 500_000L, 0L, 0L)));

        service.following(42L, 20, 0);

        verify(zsets, never()).reverseRange("uf:flws:42", 0, 499);
        verify(userCounterReader).find(42L);
    }

    @Test
    void followerThresholdEnablesLargeAuthorTopCache() {
        when(userCounterReader.find(42L))
                .thenReturn(Optional.of(new UserCounters(0L, 500_000L, 0L, 0L, 0L)));
        when(zsets.reverseRange("uf:flws:42", 0, 499)).thenReturn(Set.of("7"));

        service.following(42L, 20, 0);

        verify(zsets).reverseRange("uf:flws:42", 0, 499);
        verify(userCounterReader).find(42L);
    }

    @Test
    void missingCounterFactsDoNotTriggerVerifiedRead() {
        when(userCounterReader.find(42L)).thenReturn(Optional.empty());

        service.following(42L, 20, 0);

        verify(userCounterReader).find(42L);
        verify(userCounterReader, never()).getVerified(any(Long.class));
    }
}
