package com.tongji.notification.api.dto;

import java.time.LocalDateTime;

/**
 * 通知列表项响应。id/actorUserId/entityId/secondEntityId 用 String 序列化（snowflake 64 位 >2^53，防 JS 精度丢失）。
 */
public record NotificationItemResponse(
        String id,
        String type,
        boolean isRead,
        LocalDateTime createdAt,
        String actorUserId,
        String entityType,
        String entityId,
        String secondEntityType,
        String secondEntityId,
        Integer aggregateCount,
        LocalDateTime windowStart,
        LocalDateTime windowEnd
) {
}
