package com.tongji.relation.service;

import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounters;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.impl.RelationServiceImpl;
import com.tongji.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationBigAuthorPolicyTest {
    private RelationMapper relationMapper;
    private UserCounterReader userCounterReader;
    private RelationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        relationMapper = mock(RelationMapper.class);
        userCounterReader = mock(UserCounterReader.class);
        service = new RelationServiceImpl(relationMapper, mock(UserMapper.class), userCounterReader);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("toUserId", 7L);
        row.put("createdAt", Timestamp.from(Instant.parse("2026-06-18T10:00:00Z")));
        when(relationMapper.listFollowingRows(eq(42L), anyInt(), eq(0))).thenReturn(List.of(row));
    }

    @Test
    void belowThresholdDoesNotEnableLargeAuthorTopCache() {
        when(userCounterReader.find(42L))
                .thenReturn(Optional.of(new UserCounters(0L, 499_999L, 500_000L, 0L, 0L)));

        assertThat(service.following(42L, 20, 0)).containsExactly(7L);
        assertThat(service.following(42L, 20, 0)).containsExactly(7L);

        // 未达大V阈值：不写缓存，二次读取仍回源 DB
        verify(relationMapper, times(2)).listFollowingRows(eq(42L), anyInt(), eq(0));
        verify(userCounterReader, times(2)).find(42L);
    }

    @Test
    void followerThresholdEnablesLargeAuthorTopCache() {
        when(userCounterReader.find(42L))
                .thenReturn(Optional.of(new UserCounters(0L, 500_000L, 0L, 0L, 0L)));

        assertThat(service.following(42L, 20, 0)).containsExactly(7L);
        assertThat(service.following(42L, 20, 0)).containsExactly(7L);

        // 首次回源填充 Top 缓存，二次命中本地缓存不再回源
        verify(relationMapper, times(1)).listFollowingRows(eq(42L), anyInt(), eq(0));
    }

    @Test
    void missingCounterFactsDoNotTriggerVerifiedRead() {
        when(userCounterReader.find(42L)).thenReturn(Optional.empty());

        service.following(42L, 20, 0);

        verify(userCounterReader).find(42L);
        verify(userCounterReader, never()).getVerified(any(Long.class));
    }
}