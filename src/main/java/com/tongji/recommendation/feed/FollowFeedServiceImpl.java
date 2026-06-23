package com.tongji.recommendation.feed;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.relation.service.RelationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class FollowFeedServiceImpl implements FollowFeedService {

    /** 默认关注流页面大小，也是 timeline 缓存命中的唯一尺寸：只缓存默认页，更大 limit 请求自然旁路缓存。 */
    private static final int DEFAULT_TIMELINE_PAGE_SIZE = 20;

    /** 单次从 inbox/author_feed 拉取的原始 timeline 行数上限（混排补位可请求更大窗口）。 */
    @Value("${feed.follow.max-source-slice-limit:100}")
    private int maxSourceSliceLimit = 100;

    @Value("${feed.cache.timeline-ttl-seconds:300}")
    private long timelineCacheTtlSeconds = 300L;

    @Value("${feed.cache.author-head-ttl-seconds:120}")
    private long authorHeadCacheTtlSeconds = 120L;

    private final CqlSession cqlSession;
    private final KnowPostMapper knowPostMapper;
    private final RelationService relationService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final PreparedStatement inboxRead;
    private final PreparedStatement inboxCursorRead;
    private final PreparedStatement authorRead;
    private final PreparedStatement authorCursorRead;

    public FollowFeedServiceImpl(CqlSession cqlSession,
                                 KnowPostMapper knowPostMapper,
                                 RelationService relationService,
                                 StringRedisTemplate redisTemplate,
                                 RedissonClient redissonClient) {
        this.cqlSession = cqlSession;
        this.knowPostMapper = knowPostMapper;
        this.relationService = relationService;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.inboxRead = cqlSession.prepare("SELECT publish_ts, content_id, author_id FROM zhiguang.feed_inbox WHERE user_id = ? LIMIT ?");
        this.inboxCursorRead = cqlSession.prepare("SELECT publish_ts, content_id, author_id FROM zhiguang.feed_inbox WHERE user_id = ? AND (publish_ts, content_id) < (?, ?) LIMIT ?");
        this.authorRead = cqlSession.prepare("SELECT publish_ts, content_id FROM zhiguang.feed_author_feed WHERE author_id = ? LIMIT ?");
        this.authorCursorRead = cqlSession.prepare("SELECT publish_ts, content_id FROM zhiguang.feed_author_feed WHERE author_id = ? AND (publish_ts, content_id) < (?, ?) LIMIT ?");
    }

    @Override
    public TimelinePage getTimeline(long userId, String cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, maxSourceSliceLimit));
        Cursor decodedCursor = Cursor.parse(cursor);
        if (decodedCursor == null && safeLimit == DEFAULT_TIMELINE_PAGE_SIZE) {
            TimelinePage cached = readTimelineCache(userId);
            if (cached != null) {
                return cached;
            }
        }

        List<List<TimelineItem>> sources = new ArrayList<>();
        sources.add(readInbox(userId, decodedCursor, safeLimit));
        sources.addAll(readFollowedAuthorHeads(userId, decodedCursor, safeLimit));

        TimelinePage page = page(mergeVisiblePage(sources, decodedCursor, safeLimit), safeLimit);
        if (decodedCursor == null && safeLimit == DEFAULT_TIMELINE_PAGE_SIZE) {
            writeTimelineCache(userId, page);
        }
        return page;
    }

    private TimelinePage readTimelineCache(long userId) {
        String cached = redisTemplate.opsForValue().get("feed:timeline:" + userId);
        if (cached == null || cached.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, TimelinePage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeTimelineCache(long userId, TimelinePage page) {
        try {
            redisTemplate.opsForValue().set(
                    "feed:timeline:" + userId,
                    objectMapper.writeValueAsString(page),
                    Duration.ofSeconds(timelineCacheTtlSeconds)
            );
        } catch (Exception ignored) {}
    }

    private List<TimelineItem> readInbox(long userId, Cursor cursor, int safeLimit) {
        List<TimelineItem> out = new ArrayList<>();
        PreparedStatement statement = cursor == null ? inboxRead : inboxCursorRead;
        Object bound = cursor == null
                ? statement.bind(userId, safeLimit)
                : statement.bind(userId, cursor.publishTs(), cursor.contentId(), safeLimit);
        for (Row row : cqlSession.execute((com.datastax.oss.driver.api.core.cql.BoundStatement) bound).all()) {
            out.add(new TimelineItem(
                    row.getLong("content_id"),
                    row.getLong("author_id"),
                    row.getInstant("publish_ts")
            ));
        }
        return out;
    }

    private List<List<TimelineItem>> readFollowedAuthorHeads(long userId, Cursor cursor, int safeLimit) {
        List<List<TimelineItem>> out = new ArrayList<>();
        java.sql.Timestamp scanCreatedAt = null;
        Long scanToUserId = null;
        while (true) {
            List<FollowedAuthorRow> authorRows = relationService.listFollowedLargeAuthorRowsForFeed(userId, scanCreatedAt, scanToUserId, 100);
            if (authorRows == null || authorRows.isEmpty()) {
                return out;
            }
            for (FollowedAuthorRow authorRow : authorRows) {
                out.add(readAuthorHead(authorRow.getToUserId(), cursor, safeLimit));
            }
            if (authorRows.size() < 100) {
                return out;
            }
            FollowedAuthorRow lastRow = authorRows.getLast();
            scanToUserId = lastRow.getToUserId();
            scanCreatedAt = lastRow.getCreatedAt();
        }
    }

    private List<TimelineItem> readAuthorHead(long authorId, Cursor cursor, int safeLimit) {
        String key = "feed:author:" + authorId + ":head";
        String cached = cursor == null ? redisTemplate.opsForValue().get(key) : null;
        if (cached != null && !cached.isBlank()) {
            List<TimelineItem> items = parseItems(cached);
            if (items != null) {
                return items;
            }
        }

        RLock lock = redissonClient.getLock(key + ":lock");
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 5, TimeUnit.SECONDS);
            if (locked) {
                String again = redisTemplate.opsForValue().get(key);
                if (again != null && !again.isBlank()) {
                    List<TimelineItem> items = parseItems(again);
                    if (items != null) {
                        return items;
                    }
                }

                List<TimelineItem> headItems = loadAuthorFeed(authorId, null, safeLimit);
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(headItems), Duration.ofSeconds(authorHeadCacheTtlSeconds));
                if (cursor == null) {
                    return headItems;
                }
                return loadAuthorFeed(authorId, cursor, safeLimit);
            }
        } catch (Exception ignored) {} finally {
            if (locked) {
                lock.unlock();
            }
        }
        return loadAuthorFeed(authorId, cursor, safeLimit);
    }

    private List<TimelineItem> loadAuthorFeed(long authorId, Cursor cursor, int safeLimit) {
        List<TimelineItem> items = new ArrayList<>();
        PreparedStatement statement = cursor == null ? authorRead : authorCursorRead;
        Object bound = cursor == null
                ? statement.bind(authorId, safeLimit)
                : statement.bind(authorId, cursor.publishTs(), cursor.contentId(), safeLimit);
        for (Row row : cqlSession.execute((com.datastax.oss.driver.api.core.cql.BoundStatement) bound).all()) {
            items.add(new TimelineItem(
                    row.getLong("content_id"),
                    authorId,
                    row.getInstant("publish_ts")
            ));
        }
        return items;
    }

    private List<TimelineItem> parseItems(String cached) {
        try {
            return objectMapper.readValue(cached, new TypeReference<List<TimelineItem>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<TimelineItem> mergeVisiblePage(List<List<TimelineItem>> sources, Cursor cursor, int limit) {
        PriorityQueue<SourceCursor> heap = new PriorityQueue<>((left, right) -> compareTimelineItems(right.current(), left.current()));
        for (List<TimelineItem> source : sources) {
            if (source != null && !source.isEmpty()) {
                heap.add(new SourceCursor(source, 0));
            }
        }

        List<TimelineItem> accepted = new ArrayList<>(limit + 1);
        Set<Long> seenContentIds = new HashSet<>();
        while (!heap.isEmpty() && accepted.size() <= limit) {
            SourceCursor sourceCursor = heap.poll();
            TimelineItem item = sourceCursor.current();
            SourceCursor next = sourceCursor.advance();
            if (next != null) {
                heap.add(next);
            }
            if (!cursorAllows(cursor, item)) {
                continue;
            }
            if (!seenContentIds.add(item.contentId())) {
                continue;
            }
            KnowPost post = knowPostMapper.findById(item.contentId());
            if (isVisible(post)) {
                accepted.add(item);
            }
        }
        return accepted;
    }

    private boolean cursorAllows(Cursor cursor, TimelineItem item) {
        if (cursor == null) {
            return true;
        }
        int tsCompare = item.publishTs().compareTo(cursor.publishTs());
        if (tsCompare < 0) {
            return true;
        }
        if (tsCompare > 0) {
            return false;
        }
        return item.contentId() < cursor.contentId();
    }

    private boolean isVisible(KnowPost post) {
        if (post == null) {
            return false;
        }
        if (!"published".equals(post.getStatus())) {
            return false;
        }
        return "public".equals(post.getVisible()) || "followers".equals(post.getVisible());
    }

    private TimelinePage page(List<TimelineItem> items, int limit) {
        if (items.size() <= limit) {
            return new TimelinePage(items, null);
        }
        List<TimelineItem> slice = new ArrayList<>(items.subList(0, limit));
        TimelineItem last = slice.getLast();
        return new TimelinePage(slice, Cursor.format(last.publishTs(), last.contentId()));
    }

    private record Cursor(Instant publishTs, long contentId) {
        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int separator = raw.indexOf(':');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return new Cursor(
                    Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
                    Long.parseLong(raw.substring(separator + 1))
            );
        }

        static String format(Instant publishTs, long contentId) {
            return publishTs.toEpochMilli() + ":" + contentId;
        }
    }

    private record SourceCursor(List<TimelineItem> items, int index) {
        TimelineItem current() {
            return items.get(index);
        }

        SourceCursor advance() {
            int nextIndex = index + 1;
            return nextIndex < items.size() ? new SourceCursor(items, nextIndex) : null;
        }
    }

    private int compareTimelineItems(TimelineItem left, TimelineItem right) {
        int tsCompare = left.publishTs().compareTo(right.publishTs());
        if (tsCompare != 0) {
            return tsCompare;
        }
        return Long.compare(left.contentId(), right.contentId());
    }
}
