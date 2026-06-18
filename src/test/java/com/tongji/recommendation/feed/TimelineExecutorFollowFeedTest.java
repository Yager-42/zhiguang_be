package com.tongji.recommendation.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.tongji.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimelineExecutorFollowFeedTest {

    @Mock
    private CqlSession cqlSession;
    @Mock
    private RelationMapper relationMapper;
    @Mock
    private PreparedStatement inboxInsert;
    @Mock
    private PreparedStatement authorFeedInsert;
    @Mock
    private AsyncResultSet asyncResultSet;
    @Mock
    private BoundStatement inboxBound1;
    @Mock
    private BoundStatement inboxBound2;
    @Mock
    private BoundStatement authorBound;

    private TimelineExecutor executor;

    @BeforeEach
    void setUp() {
        when(cqlSession.prepare("INSERT INTO zhiguang.feed_inbox (user_id, publish_ts, content_id, author_id) VALUES (?, ?, ?, ?)"))
                .thenReturn(inboxInsert);
        when(cqlSession.prepare("INSERT INTO zhiguang.feed_author_feed (author_id, publish_ts, content_id) VALUES (?, ?, ?)"))
                .thenReturn(authorFeedInsert);
        when(cqlSession.executeAsync(any(BoundStatement.class)))
                .thenReturn(CompletableFuture.completedFuture(asyncResultSet));
        executor = new TimelineExecutor(cqlSession, relationMapper);
    }

    @Test
    void normalAuthorWritesInboxOnly() {
        when(relationMapper.listFollowersForFanout(7L, null, null, 256))
                .thenReturn(List.of(
                        new FanoutFollowerRow(11L, Timestamp.from(Instant.parse("2026-06-18T10:15:32Z"))),
                        new FanoutFollowerRow(12L, Timestamp.from(Instant.parse("2026-06-18T10:15:31Z")))
                ))
                .thenReturn(List.of());
        when(inboxInsert.bind(11L, Instant.parse("2026-06-18T10:15:30Z"), 101L, 7L)).thenReturn(inboxBound1);
        when(inboxInsert.bind(12L, Instant.parse("2026-06-18T10:15:30Z"), 101L, 7L)).thenReturn(inboxBound2);

        executor.fanout(new TimelineDispatch(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"), false));

        verify(cqlSession, times(2)).executeAsync(any(BoundStatement.class));
        verify(authorFeedInsert, never()).bind(any(), any(), any());
        verify(relationMapper, times(2)).listFollowersForFanout(any(), any(), any(), any(Integer.class));
    }

    @Test
    void largeAuthorWritesAuthorFeedOnly() {
        when(authorFeedInsert.bind(7L, Instant.parse("2026-06-18T10:15:30Z"), 101L)).thenReturn(authorBound);

        executor.fanout(new TimelineDispatch(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"), true));

        verify(cqlSession).executeAsync(authorBound);
        verify(relationMapper, never()).listFollowersForFanout(any(), any(), any(), any(Integer.class));
        verify(inboxInsert, never()).bind(any(), any(), any(), any());
    }

    @Test
    void replayIsIdempotentBecauseSamePrimaryKeyValuesAreReused() {
        when(relationMapper.listFollowersForFanout(7L, null, null, 256))
                .thenReturn(List.of(new FanoutFollowerRow(11L, Timestamp.from(Instant.parse("2026-06-18T10:15:32Z")))))
                .thenReturn(List.of(new FanoutFollowerRow(11L, Timestamp.from(Instant.parse("2026-06-18T10:15:32Z")))));
        when(relationMapper.listFollowersForFanout(
                7L,
                Timestamp.from(Instant.parse("2026-06-18T10:15:32Z")),
                11L,
                256
        )).thenReturn(List.of());
        when(inboxInsert.bind(11L, Instant.parse("2026-06-18T10:15:30Z"), 101L, 7L)).thenReturn(inboxBound1);

        executor.fanout(new TimelineDispatch(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"), false));
        executor.fanout(new TimelineDispatch(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"), false));

        verify(inboxInsert, times(2)).bind(11L, Instant.parse("2026-06-18T10:15:30Z"), 101L, 7L);
    }
}
