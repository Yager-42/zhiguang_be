package com.tongji.relation.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.counter.service.UserCounterReader;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.recommendation.feed.FollowedAuthorRow;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.RelationService;
import com.tongji.user.domain.User;
import com.tongji.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关系服务实现。
 * 设计要点：
 * - 关注/粉丝列表直查 MySQL（`following` 为唯一关系事实），按创建时间倒序分页；大V用户启用本地 Top 500 缓存；
 * - 计数：用户维度计数事实由 counter 模块提供；“大V”阈值仍属于关系读策略。
 */
@Service
public class RelationServiceImpl implements RelationService {
    private final RelationMapper mapper;
    private final Cache<Long, List<Long>> flwsTopCache;
    private final Cache<Long, List<Long>> fansTopCache;
    private final UserMapper userMapper;
    private final UserCounterReader userCounterReader;

    private static final int TOP_CACHE_SIZE = 500;

    /**
     * 关系服务实现构造函数。
     * @param mapper 关系表数据访问
     * @param userMapper 用户数据访问
     * @param userCounterReader 计数读取
     */
    public RelationServiceImpl(RelationMapper mapper,
                               UserMapper userMapper,
                               UserCounterReader userCounterReader) {
        this.mapper = mapper;
        this.flwsTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.fansTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.userMapper = userMapper;
        this.userCounterReader = userCounterReader;
    }

    /**
     * 获取关注列表（偏移分页），直查 DB 并截取；大V用户可命中本地 Top 缓存。
     */
    @Override
    public List<Long> following(long userId, int limit, int offset) {
        List<Long> top = flwsTopCache.getIfPresent(userId);
        if (top != null && !top.isEmpty() && offset < top.size()) {
            return new ArrayList<>(top.subList(offset, Math.min(offset + limit, top.size())));
        }
        int need = Math.max(1, Math.min(limit + offset, 1000));
        return sliceAndCache("toUserId", userId, limit, offset, mapper.listFollowingRows(userId, need, 0), flwsTopCache);
    }

    /**
     * 获取粉丝列表（偏移分页），直查 DB 并截取；大V用户可命中本地 Top 缓存。
     */
    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        List<Long> top = fansTopCache.getIfPresent(userId);
        if (top != null && !top.isEmpty() && offset < top.size()) {
            return new ArrayList<>(top.subList(offset, Math.min(offset + limit, top.size())));
        }
        int need = Math.max(1, Math.min(limit + offset, 1000));
        return sliceAndCache("fromUserId", userId, limit, offset, mapper.listFollowerRows(userId, need, 0), fansTopCache);
    }

    /**
     * 游标分页获取关注列表，按创建时间倒序；cursor 为上一页末条的毫秒时间戳（严格小于）。
     */
    @Override
    public List<Long> followingCursor(long userId, int limit, Long cursor) {
        if (cursor == null) {
            return following(userId, limit, 0);
        }
        List<Map<String, Object>> rows = mapper.listFollowingRowsCursor(
                userId, new Timestamp(cursor), null, Math.max(1, limit));
        return extractIds(rows, "toUserId");
    }

    /**
     * 游标分页获取粉丝列表，按创建时间倒序；cursor 为上一页末条的毫秒时间戳（严格小于）。
     */
    @Override
    public List<Long> followersCursor(long userId, int limit, Long cursor) {
        if (cursor == null) {
            return followers(userId, limit, 0);
        }
        List<Map<String, Object>> rows = mapper.listFollowerRowsCursor(
                userId, new Timestamp(cursor), null, Math.max(1, limit));
        return extractIds(rows, "fromUserId");
    }

    @Override
    public List<ProfileResponse> followingProfiles(long userId, int limit, int offset, Long cursor) {
        List<Long> ids = cursor != null ? followingCursor(userId, limit, cursor)
                                        : following(userId, limit, offset);
        return toProfiles(ids);
    }

    @Override
    public List<ProfileResponse> followersProfiles(long userId, int limit, int offset, Long cursor) {
        List<Long> ids = cursor != null ? followersCursor(userId, limit, cursor)
                                        : followers(userId, limit, offset);
        return toProfiles(ids);
    }

    @Override
    public List<Long> listFollowedLargeAuthorsForFeed(long userId,
                                                      Timestamp cursorCreatedAt,
                                                      Long cursorToUserId,
                                                      int limit) {
        List<FollowedAuthorRow> rows = listFollowedLargeAuthorRowsForFeed(userId, cursorCreatedAt, cursorToUserId, limit);
        List<Long> out = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return out;
        }
        for (FollowedAuthorRow row : rows) {
            out.add(row.getToUserId());
        }
        return out;
    }

    @Override
    public List<FollowedAuthorRow> listFollowedLargeAuthorRowsForFeed(long userId,
                                                                      Timestamp cursorCreatedAt,
                                                                      Long cursorToUserId,
                                                                      int limit) {
        return mapper.listFollowedAuthorsForFeed(userId, cursorCreatedAt, cursorToUserId, Math.max(1, limit));
    }

    @Override
    public Timestamp findFollowedAuthorCursorCreatedAt(long userId, long followedAuthorId) {
        return mapper.findFollowedAuthorCreatedAt(userId, followedAuthorId);
    }

    /**
     * 将用户 ID 列表映射为资料视图列表（批量查询并保持输入顺序）。
     */
    private List<ProfileResponse> toProfiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<User> users = userMapper.listByIds(ids);
        Map<Long, User> m = new LinkedHashMap<>(users.size());
        for (User u : users) m.put(u.getId(), u);
        List<ProfileResponse> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = m.get(id);
            if (u == null) continue;
            out.add(new ProfileResponse(u.getId(), u.getNickname(), u.getAvatar(), u.getBio(), u.getZgId(), u.getGender(), u.getBirthday(), u.getSchool(), u.getPhone(), u.getEmail(), u.getTagsJson()));
        }
        return out;
    }

    /**
     * 判断是否为大V（基于 followers 计数阈值）。
     * @param userId 用户ID
     * @return 是否为大V
     */
    private boolean isBigV(long userId) {
        return userCounterReader.find(userId)
                .map(counters -> counters.followers() >= 500_000L)
                .orElse(false);
    }

    /**
     * 从 DB 行列表按偏移截取 ID，并维护大V用户本地 Top 缓存（仅首页取数时，保证缓存内容就是 Top N）。
     */
    private List<Long> sliceAndCache(String idKey,
                                     long userId,
                                     int limit,
                                     int offset,
                                     List<Map<String, Object>> rows,
                                     Cache<Long, List<Long>> localCache) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = extractIds(rows, idKey);
        int from = Math.min(offset, ids.size());
        int to = Math.min(offset + limit, ids.size());
        if (offset == 0 && localCache != null && isBigV(userId)) {
            localCache.put(userId, new ArrayList<>(ids.subList(0, Math.min(ids.size(), TOP_CACHE_SIZE))));
        }
        return new ArrayList<>(ids.subList(from, to));
    }

    /**
     * 按行顺序提取主 ID（toUserId/fromUserId 列）。
     */
    private List<Long> extractIds(List<Map<String, Object>> rows, String idKey) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object id = row.get(idKey);
            if (id instanceof Number n) {
                out.add(n.longValue());
            }
        }
        return out;
    }
}