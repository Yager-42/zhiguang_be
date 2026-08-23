package com.tongji.favorite.event;

/**
 * 收藏关系变化事件；MySQL 关系提交后用于派生 Redis 状态、计数与推荐反馈。
 *
 * @param eventId Outbox 事件 ID，跨重投保持不变
 * @param eventType 事件类型，固定为 {@code FavoriteChanged}
 * @param schemaVersion 载荷版本，当前为 1
 * @param userId 操作用户 ID
 * @param postId 目标知文 ID
 * @param faved 变更后的绝对收藏状态
 * @param delta 收藏总数增量，只允许 {@code 1} 或 {@code -1}
 * @param occurredAt 事件发生时间，Unix 毫秒
 * @since 2026-08-21
 */
public record FavoriteChangedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        long userId,
        long postId,
        boolean faved,
        int delta,
        long occurredAt
) {
    public static final String TYPE = "FavoriteChanged";
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
