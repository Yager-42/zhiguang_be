package com.tongji.notification.service;

import com.tongji.notification.api.dto.NotificationPageResponse;
import com.tongji.notification.api.dto.NotificationUnreadCountResponse;
import com.tongji.notification.model.Notification;

import java.time.LocalDateTime;

public interface NotificationService {
    void create(Notification notification);

    NotificationPageResponse page(long recipientUserId, LocalDateTime cursorCreatedAt, Long cursorId, int limit);

    NotificationUnreadCountResponse unreadCount(long recipientUserId);

    void markRead(long recipientUserId, long notificationId);

    void markAllRead(long recipientUserId);
}
