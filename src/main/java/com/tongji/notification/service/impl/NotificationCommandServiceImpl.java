package com.tongji.notification.service.impl;

import com.tongji.notification.model.LikeNotificationBucket;
import com.tongji.notification.model.Notification;
import com.tongji.notification.model.NotificationType;
import com.tongji.notification.service.NotificationCommandService;
import com.tongji.notification.service.NotificationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class NotificationCommandServiceImpl implements NotificationCommandService {

    private final NotificationService notificationService;

    public NotificationCommandServiceImpl(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void createFollowNotification(long actorUserId, long recipientUserId, String eventKey) {
        notificationService.create(Notification.builder()
                .recipientUserId(recipientUserId)
                .actorUserId(actorUserId)
                .type(NotificationType.FOLLOW)
                .entityType("user")
                .entityId(recipientUserId)
                .eventKey(eventKey)
                .build());
    }

    @Override
    public void createCommentNotification(long actorUserId,
                                          long recipientUserId,
                                          long postId,
                                          long commentId,
                                          String eventKey) {
        notificationService.create(Notification.builder()
                .recipientUserId(recipientUserId)
                .actorUserId(actorUserId)
                .type(NotificationType.COMMENT)
                .entityType("knowpost")
                .entityId(postId)
                .secondEntityType("comment")
                .secondEntityId(commentId)
                .eventKey(eventKey)
                .build());
    }

    @Override
    public void createLikeNotification(LikeNotificationBucket bucket) {
        notificationService.create(Notification.builder()
                .recipientUserId(bucket.getRecipientUserId())
                .actorUserId(bucket.getLatestActorUserId())
                .type(NotificationType.LIKE)
                .entityType(bucket.getEntityType())
                .entityId(bucket.getEntityId())
                .eventKey(buildLikeEventKey(bucket))
                .aggregateCount(bucket.getCount())
                .windowStart(toLocalDateTime(bucket.getWindowStartEpochMillis()))
                .windowEnd(toLocalDateTime(bucket.getWindowEndEpochMillis()))
                .createdAt(toLocalDateTime(bucket.getLatestEventAt()))
                .build());
    }

    private String buildLikeEventKey(LikeNotificationBucket bucket) {
        return "like:bucket:%d:%s:%d:%d".formatted(
                bucket.getRecipientUserId(),
                bucket.getEntityType(),
                bucket.getEntityId(),
                bucket.getWindowStartEpochMillis()
        );
    }

    private LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
