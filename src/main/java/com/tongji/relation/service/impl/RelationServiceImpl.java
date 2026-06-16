package com.tongji.relation.service.impl;

import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.RelationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.tongji.user.mapper.UserMapper;
import com.tongji.user.domain.User;
import com.tongji.profile.api.dto.ProfileResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.sql.Timestamp;
import java.util.Date;
import java.util.function.IntFunction;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;

/**
 * 关系服务实现。
 * 设计要点：
 * - 读路径：优先读取 Redis ZSet（关注/粉丝）并按需回填，支持偏移与游标两种分页；大V用户启用本地 Top 缓存；
 * - 计数：用户维度计数（关注/粉丝等）通过独立服务维护，阈值判断如“大V”基于 SDS 段值；
 * - 并发与一致性：回填后设置短 TTL，降低陈旧风险。
 */
@Service
public class RelationServiceImpl implements RelationService {
    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final Cache<Long, List<Long>> flwsTopCache;
    private final Cache<Long, List<Long>> fansTopCache;
    private final UserMapper userMapper;
    

    /**
     * 关系服务实现构造函数。
     * @param mapper 关系表数据访问
     * @param redis Redis 客户端
     * @param userMapper 用户数据访问
     */
    public RelationServiceImpl(RelationMapper mapper,
                               StringRedisTemplate redis,
                               UserMapper userMapper) {
        this.mapper = mapper;
        this.redis = redis;
        this.flwsTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.fansTopCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
        this.userMapper = userMapper;
    }

    /**
     * 获取关注列表（偏移分页），优先读取 Redis ZSet，未命中时回填并设置 TTL。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> following(long userId, int limit, int offset) {
        String key = "uf:flws:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt",
                flwsTopCache,
                userId
        );
    }

    /**
     * 获取粉丝列表（偏移分页），ZSet 优先，DB 回填并设置 TTL。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        String key = "uf:fans:" + userId;
        return getListWithOffset(
                key,
                offset,
                limit,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt",
                fansTopCache,
                userId
        );
    }

    /**
     * 游标分页获取关注列表，按创建时间倒序基于 ZSet 分数。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 关注的用户ID列表
     */
    @Override
    public List<Long> followingCursor(long userId, int limit, Long cursor) {
        String key = "uf:flws:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt"
        );
    }

    /**
     * 游标分页获取粉丝列表。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 粉丝用户ID列表
     */
    @Override
    public List<Long> followersCursor(long userId, int limit, Long cursor) {
        String key = "uf:fans:" + userId;
        return getListWithCursor(
                key,
                limit,
                cursor,
                need -> mapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt"
        );
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
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(("ucnt:" + userId).getBytes(StandardCharsets.UTF_8)));

        if (raw == null || raw.length < 20) {
            return false;
        }

        long n = 0;
        int off = 2 * 4;

        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (raw[off + i] & 0xFFL);
        }

        return n >= 500_000L;
    }

    /**
     * 偏移分页读取：优先命中 ZSet，未命中时从 DB 回填并设置 TTL；大V用户维护本地 Top 缓存以降低冷启动开销。
     */
    private List<Long> getListWithOffset(
            String key,
            int offset,
            int limit,
            IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
            String idField,
            String tsField,
            Cache<Long, List<Long>> localCache,
            long userId
    ) {
        // 1. 先查本地缓存 (L1)
        List<Long> top = localCache != null ? localCache.getIfPresent(userId) : null;
        if (top != null && !top.isEmpty()) {
            // 本地缓存通常只存 Top N (例如前500)，如果 offset 在范围内则直接返回
            if (offset < top.size()) {
                int to = Math.min(offset + limit, top.size());
                return new ArrayList<>(top.subList(offset, to));
            }
            // 如果请求的 offset 超过了本地缓存范围，继续查 Redis
        }

        // 2. 再查 Redis (L2)
        Set<String> cached = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        // 3. 最后查 DB 回填
        int need = Math.max(1, limit + offset);
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));
        if (rows != null && !rows.isEmpty()) {
            fillZSet(key, rows, idField, tsField, null);
            redis.expire(key, Duration.ofHours(2));

            // 回填后尝试更新本地缓存（仅针对大V）
            if (localCache != null && isBigV(userId)) {
                maybeUpdateTopCache(userId, key, localCache);
            }

            Set<String> filled = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1L);
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }
        return Collections.emptyList();
    }

    /**
     * 游标分页读取：按分数（毫秒时间戳）倒序读取；未命中时回填满足所需范围的数据并继续读取。
     */
    private List<Long> getListWithCursor(String key,
                                         int limit,
                                         Long cursor,
                                         IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
                                         String idField,
                                         String tsField) {

        double max = cursor == null ? Double.POSITIVE_INFINITY : cursor.doubleValue();
        Set<String> cached = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);

        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        int need = Math.max(limit, 100);
        Map<Long, Map<String, Object>> rows = rowsFetcher.apply(Math.min(need, 1000));

        if (rows != null && !rows.isEmpty()) {
            fillZSet(key, rows, idField, tsField, cursor);
            redis.expire(key, Duration.ofHours(2));
            Set<String> filled = redis.opsForZSet().reverseRangeByScore(key, Double.NEGATIVE_INFINITY, max, 0, limit);
            return filled == null ? Collections.emptyList() : toLongList(filled);
        }
        return Collections.emptyList();
    }

    /**
     * 将行数据填充至 ZSet：分值为创建时间戳；若提供游标则只填充不高于游标的记录。
     */
    private void fillZSet(String key,
                          Map<Long, Map<String, Object>> rows,
                          String idField,
                          String tsField,
                          Long cursor) {
        for (Map<String, Object> r : rows.values()) {
            Object idObj = r.get(idField);
            Object tsObj = r.get(tsField);
            if (idObj == null || tsObj == null) continue;
            long score = tsScore(tsObj);
            if (cursor == null || score <= cursor) {
                redis.opsForZSet().add(key, String.valueOf(idObj), score);
            }
        }
    }

    /**
     * 将多类型时间对象统一转换为毫秒分值。
     */
    private long tsScore(Object tsObj) {
        if (tsObj instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (tsObj instanceof Date d) {
            return d.getTime();
        }
        return System.currentTimeMillis();
    }

    /**
     * 将字符串集合按原顺序映射为长整型列表。
     */
    private List<Long> toLongList(Set<String> set) {
        List<Long> out = new ArrayList<>(set.size());
        for (String s : set) out.add(Long.valueOf(s));
        return out;
    }

    /**
     * 更新本地 Top 缓存：大V 用户仅缓存前 500 名，减少频繁回源与排序成本。
     */
    private void maybeUpdateTopCache(long userId, String key, Cache<Long, List<Long>> cache) {
        Set<String> allSet = redis.opsForZSet().reverseRange(key, 0, 499);
        if (allSet == null || allSet.isEmpty()) return;
        List<Long> all = new ArrayList<>(allSet.size());
        for (String s : allSet) all.add(Long.valueOf(s));
        cache.put(userId, all);
    }
}
