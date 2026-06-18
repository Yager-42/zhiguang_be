package com.tongji.recommendation.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.recommendation.feed.FollowedAuthorRow;
import com.tongji.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowFeedServiceTest {

    @Mock
    private CqlSession cqlSession;
    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private RelationService relationService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock authorHeadLock;
    @Mock
    private PreparedStatement inboxRead;
    @Mock
    private PreparedStatement authorRead;
    @Mock
    private PreparedStatement inboxCursorRead;
    @Mock
    private PreparedStatement authorCursorRead;
    @Mock
    private BoundStatement inboxReadBound;
    @Mock
    private BoundStatement authorReadBound;
    @Mock
    private BoundStatement inboxCursorReadBound;
    @Mock
    private BoundStatement authorCursorReadBound;
    @Mock
    private ResultSet inboxResultSet;
    @Mock
    private ResultSet authorResultSet;
    @Mock
    private Row inboxRow;
    @Mock
    private Row author101;
    @Mock
    private Row author100;
    @Mock
    private Row author99;

    private FollowFeedServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(cqlSession.prepare("SELECT publish_ts, content_id, author_id FROM zhiguang.feed_inbox WHERE user_id = ? LIMIT ?"))
                .thenReturn(inboxRead);
        when(cqlSession.prepare("SELECT publish_ts, content_id, author_id FROM zhiguang.feed_inbox WHERE user_id = ? AND (publish_ts, content_id) < (?, ?) LIMIT ?"))
                .thenReturn(inboxCursorRead);
        when(cqlSession.prepare("SELECT publish_ts, content_id FROM zhiguang.feed_author_feed WHERE author_id = ? LIMIT ?"))
                .thenReturn(authorRead);
        when(cqlSession.prepare("SELECT publish_ts, content_id FROM zhiguang.feed_author_feed WHERE author_id = ? AND (publish_ts, content_id) < (?, ?) LIMIT ?"))
                .thenReturn(authorCursorRead);
        when(inboxRead.bind(anyLong(), eq(20))).thenReturn(inboxReadBound);
        service = new FollowFeedServiceImpl(cqlSession, knowPostMapper, relationService, redisTemplate, redissonClient);
    }

    @Test
    void largeAuthorOnlyFollowReturnsAuthorHeadEvenWhenInboxIsEmpty() throws Exception {
        when(redissonClient.getLock("feed:author:7:head:lock")).thenReturn(authorHeadLock);
        when(authorHeadLock.tryLock(0, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(authorRead.bind(anyLong(), eq(20))).thenReturn(authorReadBound);
        when(valueOperations.get("feed:timeline:42")).thenReturn(null);
        when(valueOperations.get("feed:author:7:head")).thenReturn(null);
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100))
                .thenReturn(List.of(row(7L, "2026-06-18T10:00:00Z")));
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet, authorResultSet);
        when(inboxResultSet.all()).thenReturn(List.of());
        when(authorResultSet.all()).thenReturn(List.of(author101));
        when(author101.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:30Z"));
        when(author101.getLong("content_id")).thenReturn(101L);
        when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, "published", "followers", Instant.parse("2026-06-18T10:15:30Z")));

        TimelinePage page = service.getTimeline(42L, null, 20);

        assertThat(page.items()).extracting(TimelineItem::contentId).containsExactly(101L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void readTimeRepairFiltersDeletedPrivateAndSchoolPosts() throws Exception {
        when(redissonClient.getLock("feed:author:7:head:lock")).thenReturn(authorHeadLock);
        when(authorHeadLock.tryLock(0, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(authorRead.bind(anyLong(), eq(20))).thenReturn(authorReadBound);
        when(valueOperations.get("feed:timeline:42")).thenReturn(null);
        when(valueOperations.get("feed:author:7:head")).thenReturn(null);
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100))
                .thenReturn(List.of(row(7L, "2026-06-18T10:00:00Z")));
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet, authorResultSet);
        when(inboxResultSet.all()).thenReturn(List.of(inboxRow));
        when(authorResultSet.all()).thenReturn(List.of(author101, author100, author99));

        when(inboxRow.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:31Z"));
        when(inboxRow.getLong("content_id")).thenReturn(200L);
        when(inboxRow.getLong("author_id")).thenReturn(8L);

        when(author101.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:30Z"));
        when(author101.getLong("content_id")).thenReturn(101L);
        when(author100.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:29Z"));
        when(author100.getLong("content_id")).thenReturn(100L);
        when(author99.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:28Z"));
        when(author99.getLong("content_id")).thenReturn(99L);

        when(knowPostMapper.findById(200L)).thenReturn(post(200L, 8L, "published", "public", Instant.parse("2026-06-18T10:15:31Z")));
        when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, "deleted", "public", Instant.parse("2026-06-18T10:15:30Z")));
        when(knowPostMapper.findById(100L)).thenReturn(post(100L, 7L, "published", "private", Instant.parse("2026-06-18T10:15:29Z")));
        when(knowPostMapper.findById(99L)).thenReturn(post(99L, 7L, "published", "school", Instant.parse("2026-06-18T10:15:28Z")));

        TimelinePage page = service.getTimeline(42L, null, 20);

        assertThat(page.items()).extracting(TimelineItem::contentId).containsExactly(200L);
    }

    @Test
    void cursorStaysStableAcrossSameTimestampPosts() {
        when(valueOperations.get("feed:author:7:head")).thenReturn(null);
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100))
                .thenReturn(List.of(row(7L, "2026-06-18T10:00:00Z")));
        when(authorRead.bind(anyLong(), eq(20))).thenReturn(authorReadBound);
        when(inboxCursorRead.bind(anyLong(), any(Instant.class), anyLong(), eq(20))).thenReturn(inboxCursorReadBound);
        when(authorCursorRead.bind(anyLong(), any(Instant.class), anyLong(), eq(20))).thenReturn(authorCursorReadBound);
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet, authorResultSet, inboxResultSet, authorResultSet, authorResultSet);
        when(inboxResultSet.all()).thenReturn(List.of(inboxRow));
        when(authorResultSet.all()).thenReturn(List.of(author101, author100));

        Instant sameTs = Instant.parse("2026-06-18T10:15:30Z");
        when(inboxRow.getInstant("publish_ts")).thenReturn(sameTs);
        when(inboxRow.getLong("content_id")).thenReturn(102L);
        when(inboxRow.getLong("author_id")).thenReturn(8L);
        when(author101.getInstant("publish_ts")).thenReturn(sameTs);
        when(author101.getLong("content_id")).thenReturn(101L);
        when(author100.getInstant("publish_ts")).thenReturn(sameTs);
        when(author100.getLong("content_id")).thenReturn(100L);

        when(knowPostMapper.findById(102L)).thenReturn(post(102L, 8L, "published", "public", sameTs));
        when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, "published", "followers", sameTs));
        when(knowPostMapper.findById(100L)).thenReturn(post(100L, 7L, "published", "followers", sameTs));

        TimelinePage firstPage = service.getTimeline(42L, null, 2);
        TimelinePage secondPage = service.getTimeline(42L, firstPage.nextCursor(), 2);

        assertThat(firstPage.items()).extracting(TimelineItem::contentId).containsExactly(102L, 101L);
        assertThat(firstPage.nextCursor()).isEqualTo("1781777730000:101");
        assertThat(secondPage.items()).extracting(TimelineItem::contentId).containsExactly(100L);
        verify(inboxCursorRead).bind(42L, sameTs, 101L, 20);
        verify(authorCursorRead).bind(7L, sameTs, 101L, 20);
    }

    @Test
    void readsOlderFollowedLargeAuthorsTooWhenTheyHaveNewerPosts() {
        Map<String, String> cache = new HashMap<>();
        cache.put("feed:timeline:42", null);
        for (int i = 1; i <= 100; i++) {
            cache.put("feed:author:" + i + ":head", timelineJson(i * 100L, i, Instant.parse("2026-06-18T09:00:00Z").minusSeconds(i)));
        }
        cache.put("feed:author:101:head", timelineJson(10100L, 101L, Instant.parse("2026-06-18T10:30:00Z")));
        when(valueOperations.get(any())).thenAnswer(invocation -> cache.get(invocation.getArgument(0, String.class)));
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet);
        when(inboxResultSet.all()).thenReturn(List.of());
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100)).thenReturn(authorRows(1, 100));
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, java.sql.Timestamp.from(Instant.parse("2026-06-18T08:00:00Z")), 100L, 100))
                .thenReturn(List.of(row(101L, "2026-06-18T07:59:00Z")));
        when(knowPostMapper.findById(10100L)).thenReturn(post(10100L, 101L, "published", "followers", Instant.parse("2026-06-18T10:30:00Z")));
        when(knowPostMapper.findById(100L)).thenReturn(post(100L, 1L, "published", "followers", Instant.parse("2026-06-18T09:00:00Z").minusSeconds(1)));

        TimelinePage page = service.getTimeline(42L, null, 20);

        assertThat(page.items()).extracting(TimelineItem::contentId).contains(10100L);
        assertThat(page.items().getFirst().contentId()).isEqualTo(10100L);
        verify(relationService, never()).findFollowedAuthorCursorCreatedAt(anyLong(), anyLong());
    }

    @Test
    void smallPageHydratesOnlyNeededCandidatesFromCollectedLargeAuthorHeads() {
        Map<String, String> cache = new HashMap<>();
        cache.put("feed:timeline:42", null);
        cache.put("feed:author:7:head", timelineJson(103L, 7L, Instant.parse("2026-06-18T10:15:32Z")));
        cache.put("feed:author:8:head", timelineJson(102L, 8L, Instant.parse("2026-06-18T10:15:31Z")));
        cache.put("feed:author:9:head", timelineJson(101L, 9L, Instant.parse("2026-06-18T10:15:30Z")));
        when(valueOperations.get(any())).thenAnswer(invocation -> cache.get(invocation.getArgument(0, String.class)));
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet);
        when(inboxResultSet.all()).thenReturn(List.of());
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100))
                .thenReturn(List.of(
                        row(7L, "2026-06-18T10:00:03Z"),
                        row(8L, "2026-06-18T10:00:02Z"),
                        row(9L, "2026-06-18T10:00:01Z")
                ));
        when(knowPostMapper.findById(103L)).thenReturn(post(103L, 7L, "published", "followers", Instant.parse("2026-06-18T10:15:32Z")));
        when(knowPostMapper.findById(102L)).thenReturn(post(102L, 8L, "published", "followers", Instant.parse("2026-06-18T10:15:31Z")));

        TimelinePage page = service.getTimeline(42L, null, 1);

        assertThat(page.items()).extracting(TimelineItem::contentId).containsExactly(103L);
        assertThat(page.nextCursor()).isEqualTo("1781777732000:103");
        verify(knowPostMapper).findById(103L);
        verify(knowPostMapper).findById(102L);
        verify(knowPostMapper, never()).findById(101L);
        verify(knowPostMapper, times(2)).findById(anyLong());
    }

    @Test
    void usesConfiguredTimelineAndAuthorHeadTtls() throws Exception {
        ReflectionTestUtils.setField(service, "timelineCacheTtlSeconds", 11L);
        ReflectionTestUtils.setField(service, "authorHeadCacheTtlSeconds", 22L);
        when(redissonClient.getLock("feed:author:7:head:lock")).thenReturn(authorHeadLock);
        when(authorHeadLock.tryLock(0, 5, TimeUnit.SECONDS)).thenReturn(true);
        when(authorRead.bind(anyLong(), eq(20))).thenReturn(authorReadBound);
        when(valueOperations.get("feed:timeline:42")).thenReturn(null);
        when(valueOperations.get("feed:author:7:head")).thenReturn(null);
        when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100))
                .thenReturn(List.of(row(7L, "2026-06-18T10:00:00Z")));
        when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet, authorResultSet);
        when(inboxResultSet.all()).thenReturn(List.of());
        when(authorResultSet.all()).thenReturn(List.of(author101));
        when(author101.getInstant("publish_ts")).thenReturn(Instant.parse("2026-06-18T10:15:30Z"));
        when(author101.getLong("content_id")).thenReturn(101L);
        when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, "published", "followers", Instant.parse("2026-06-18T10:15:30Z")));

        service.getTimeline(42L, null, 20);

        verify(valueOperations).set(eq("feed:timeline:42"), any(String.class), eq(Duration.ofSeconds(11)));
        verify(valueOperations).set(eq("feed:author:7:head"), any(String.class), eq(Duration.ofSeconds(22)));
    }

    private List<Long> authorIds(int fromInclusive, int toInclusive) {
        List<Long> out = new java.util.ArrayList<>();
        for (long i = fromInclusive; i <= toInclusive; i++) {
            out.add(i);
        }
        return out;
    }

    private List<FollowedAuthorRow> authorRows(int fromInclusive, int toInclusive) {
        List<FollowedAuthorRow> out = new java.util.ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            out.add(row(i, "2026-06-18T09:40:%02dZ".formatted((100 - i) % 60)));
        }
        out.set(out.size() - 1, row(toInclusive, "2026-06-18T08:00:00Z"));
        return out;
    }

    private FollowedAuthorRow row(long authorId, String createdAt) {
        return new FollowedAuthorRow(authorId, java.sql.Timestamp.from(Instant.parse(createdAt)));
    }

    private String timelineJson(long contentId, long authorId, Instant publishTs) {
        return "[{\"contentId\":%d,\"authorId\":%d,\"publishTs\":\"%s\"}]".formatted(contentId, authorId, publishTs);
    }

    private KnowPost post(long id, long authorId, String status, String visible, Instant publishTime) {
        return KnowPost.builder()
                .id(id)
                .creatorId(authorId)
                .status(status)
                .visible(visible)
                .publishTime(publishTime)
                .title("post-" + id)
                .build();
    }
}
