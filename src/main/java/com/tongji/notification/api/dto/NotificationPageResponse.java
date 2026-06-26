package com.tongji.notification.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record NotificationPageResponse(
        List<NotificationItemResponse> items,
        LocalDateTime nextCursorCreatedAt,
        Long nextCursorId,
        boolean hasMore
) {
}
