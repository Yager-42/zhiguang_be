package com.tongji.favorite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.favorite.event.FavoriteChangedEvent;
import com.tongji.favorite.mapper.FavoriteMapper;
import com.tongji.favorite.model.UserFavorite;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import com.tongji.outbox.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 维护 MySQL 收藏事实及其同事务 Outbox，并提供当前用户的收藏列表。
 *
 * <p>本服务不直接修改 Redis 或发送 Kafka；缓存与计数只由提交后的事件派生。</p>
 *
 * @since 2026-08-21
 */
@Service
public class FavoriteService {
    public static final String AGGREGATE_TYPE = "user_favorite";
    public static final String EVENT_TYPE = FavoriteChangedEvent.TYPE;
    private static final String ENTITY_TYPE = "knowpost";
    private static final int MAX_PAGE_SIZE = 50;

    private final FavoriteMapper favoriteMapper;
    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final KnowPostFeedService knowPostFeedService;
    private final KnowPostService knowPostService;

    public FavoriteService(FavoriteMapper favoriteMapper,
                           OutboxMapper outboxMapper,
                           IdService idService,
                           ObjectMapper objectMapper,
                           KnowPostFeedService knowPostFeedService,
                           KnowPostService knowPostService) {
        this.favoriteMapper = favoriteMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.knowPostFeedService = knowPostFeedService;
        this.knowPostService = knowPostService;
    }

    /**
     * 收藏知文；关系和事件在一个 MySQL 事务内原子提交。
     *
     * @param userId 当前登录用户 ID
     * @param entityType 实体类型，只允许 {@code knowpost}
     * @param entityId 十进制知文 ID
     * @return 写入结果；重复收藏返回 {@code changed=false, faved=true}
     */
    @Transactional
    public FavoriteWriteResult favorite(long userId, String entityType, String entityId) {
        long postId = requireKnowPostId(entityType, entityId);
        if (!knowPostService.isPublished(postId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知文不存在或不可收藏");
        }
        Instant occurredAt = Instant.now();
        int changed = favoriteMapper.insertIgnore(userId, postId, occurredAt);
        if (changed == 1) {
            writeOutbox(userId, postId, true, 1, occurredAt);
        }
        return new FavoriteWriteResult(changed == 1, true);
    }

    /**
     * 取消收藏知文；关系和事件在一个 MySQL 事务内原子提交。
     *
     * @param userId 当前登录用户 ID
     * @param entityType 实体类型，只允许 {@code knowpost}
     * @param entityId 十进制知文 ID
     * @return 写入结果；重复取消返回 {@code changed=false, faved=false}
     */
    @Transactional
    public FavoriteWriteResult unfavorite(long userId, String entityType, String entityId) {
        long postId = requireKnowPostId(entityType, entityId);
        Instant occurredAt = Instant.now();
        int changed = favoriteMapper.delete(userId, postId);
        if (changed == 1) {
            writeOutbox(userId, postId, false, -1, occurredAt);
        }
        return new FavoriteWriteResult(changed == 1, false);
    }

    /**
     * 使用不透明游标分页查询当前用户收藏，并批量组装仍可访问的知文。
     *
     * @param userId 当前登录用户 ID
     * @param cursor 上一页返回的游标；第一页为空
     * @param size 每页数量，服务端限制为 1 到 50
     * @return 收藏知文页；不可访问或已删除知文不会返回
     */
    public FeedPageResponse list(long userId, String cursor, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        FavoriteCursor parsedCursor = FavoriteCursor.parse(cursor);
        List<UserFavorite> rows = favoriteMapper.listPage(
                userId,
                parsedCursor == null ? null : parsedCursor.createdAt(),
                parsedCursor == null ? null : parsedCursor.postId(),
                safeSize + 1
        );
        boolean hasMore = rows.size() > safeSize;
        List<UserFavorite> pageRows = hasMore ? rows.subList(0, safeSize) : rows;
        List<Long> postIds = pageRows.stream().map(UserFavorite::getPostId).toList();
        List<FeedItemResponse> items = knowPostFeedService.getFeedByIds(
                postIds,
                userId,
                KnowPostFeedService.FeedVisibilityScope.FOLLOW
        );
        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            UserFavorite last = pageRows.getLast();
            nextCursor = new FavoriteCursor(last.getCreatedAt(), last.getPostId()).encode();
        }
        return new FeedPageResponse(items, 1, safeSize, hasMore, nextCursor);
    }

    private void writeOutbox(long userId, long postId, boolean faved, int delta, Instant occurredAt) {
        long eventId = idService.nextId(IdNamespace.OUTBOX_EVENT);
        FavoriteChangedEvent event = new FavoriteChangedEvent(
                String.valueOf(eventId),
                FavoriteChangedEvent.TYPE,
                FavoriteChangedEvent.CURRENT_SCHEMA_VERSION,
                userId,
                postId,
                faved,
                delta,
                occurredAt.toEpochMilli()
        );
        try {
            outboxMapper.insert(
                    eventId,
                    AGGREGATE_TYPE,
                    userId,
                    EVENT_TYPE,
                    objectMapper.writeValueAsString(event)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "收藏事件序列化失败");
        }
    }

    private long requireKnowPostId(String entityType, String entityId) {
        if (!ENTITY_TYPE.equals(entityType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "收藏仅支持知文");
        }
        try {
            long postId = Long.parseLong(entityId);
            if (postId <= 0) {
                throw new NumberFormatException("non-positive post id");
            }
            return postId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知文 ID 非法");
        }
    }

    private record FavoriteCursor(Instant createdAt, long postId) {
        private static FavoriteCursor parse(String cursor) {
            if (cursor == null) {
                return null;
            }
            int separator = cursor.indexOf(':');
            if (cursor.isBlank() || separator <= 0 || separator == cursor.length() - 1) {
                throw invalidCursor();
            }
            try {
                long epochMillis = Long.parseLong(cursor.substring(0, separator));
                long postId = Long.parseLong(cursor.substring(separator + 1));
                if (epochMillis < 0 || postId <= 0) {
                    throw invalidCursor();
                }
                return new FavoriteCursor(Instant.ofEpochMilli(epochMillis), postId);
            } catch (NumberFormatException exception) {
                throw invalidCursor();
            }
        }

        private String encode() {
            return createdAt.toEpochMilli() + ":" + postId;
        }

        private static BusinessException invalidCursor() {
            return new BusinessException(ErrorCode.BAD_REQUEST, "cursor 非法");
        }
    }
}
