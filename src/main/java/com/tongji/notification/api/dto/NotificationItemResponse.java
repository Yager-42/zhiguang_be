package com.tongji.notification.api.dto;

import java.time.LocalDateTime;

public record NotificationItemResponse(
        Long id,
        String type,
        boolean isRead,
        LocalDateTime createdAt,
        Long actorUserId,
        String entityType,
        Long entityId,
        String secondEntityType,
        Long secondEntityId,
        Integer aggregateCount,
        LocalDateTime windowStart,
        LocalDateTime windowEnd
) {
}
