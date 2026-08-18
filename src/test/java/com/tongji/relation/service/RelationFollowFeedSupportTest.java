package com.tongji.relation.service;

import com.tongji.counter.service.UserCounterReader;
import com.tongji.recommendation.feed.FollowedAuthorRow;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.impl.RelationServiceImpl;
import com.tongji.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationFollowFeedSupportTest {

    @Mock
    private RelationMapper relationMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserCounterReader userCounterReader;

    @Test
    void followedAuthorDiscoveryScansAllPagesAndDoesNotFilterByCurrentFollowerCount() {
        RelationServiceImpl service = new RelationServiceImpl(relationMapper, userMapper, userCounterReader);
        Timestamp firstTs = Timestamp.from(Instant.parse("2026-06-18T10:15:32Z"));

        when(relationMapper.listFollowedAuthorsForFeed(42L, null, null, 100))
                .thenReturn(List.of(new FollowedAuthorRow(6L, firstTs), new FollowedAuthorRow(7L, firstTs)));

        List<Long> authorIds = service.listFollowedLargeAuthorsForFeed(42L, null, null, 100);

        assertThat(authorIds).containsExactly(6L, 7L);
        verify(relationMapper, never()).countFollowerActive(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cursorCreatedAtLookupContinuesBeyondFirstFollowedAuthorWindow() {
        RelationServiceImpl service = new RelationServiceImpl(relationMapper, userMapper, userCounterReader);
        Timestamp targetTs = Timestamp.from(Instant.parse("2026-06-18T08:00:00Z"));

        when(relationMapper.findFollowedAuthorCreatedAt(42L, 5_000L)).thenReturn(targetTs);

        assertThat(service.findFollowedAuthorCursorCreatedAt(42L, 5_000L)).isEqualTo(targetTs);
        verify(relationMapper, never()).listFollowedAuthorsForFeed(42L, null, null, 1000);
    }
}
