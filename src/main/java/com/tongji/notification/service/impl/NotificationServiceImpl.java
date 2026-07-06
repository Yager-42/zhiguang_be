package com.tongji.notification.service.impl;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.notification.api.dto.NotificationItemResponse;
import com.tongji.notification.api.dto.NotificationPageResponse;
import com.tongji.notification.api.dto.NotificationUnreadCountResponse;
import com.tongji.notification.mapper.NotificationMapper;
import com.tongji.notification.model.Notification;
import com.tongji.notification.service.NotificationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final NotificationMapper notificationMapper;
    private final IdService idService;

    public NotificationServiceImpl(NotificationMapper notificationMapper, IdService idService) {
        this.notificationMapper = notificationMapper;
        this.idService = idService;
    }

    @Override
    public void create(Notification notification) {
        if (notification.getRecipientUserId() == null
                || notification.getActorUserId() == null
                || notification.getRecipientUserId().equals(notification.getActorUserId())) {
            return;
        }
        LocalDateTime now = notification.getCreatedAt() == null ? LocalDateTime.now() : notification.getCreatedAt();
        notification.setId(idService.nextId(IdNamespace.NOTIFICATION));
        notification.setCreatedAt(now);
        if (notification.getAggregateCount() == null || notification.getAggregateCount() < 1) {
            notification.setAggregateCount(1);
        }
        if (notification.getIsRead() == null) {
            notification.setIsRead(0);
        }
        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException ignored) {
        }
    }

    @Override
    public NotificationPageResponse page(long recipientUserId, LocalDateTime cursorCreatedAt, Long cursorId, int limit) {
        int safeLimit = normalizeLimit(limit);
        List<Notification> rows = notificationMapper.listByRecipient(recipientUserId, cursorCreatedAt, cursorId, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<Notification> pageRows = hasMore ? rows.subList(0, safeLimit) : rows;
        LocalDateTime nextCursorCreatedAt = null;
        String nextCursorId = null;
        if (hasMore && !pageRows.isEmpty()) {
            Notification last = pageRows.get(pageRows.size() - 1);
            nextCursorCreatedAt = last.getCreatedAt();
            nextCursorId = String.valueOf(last.getId());
        }
        return new NotificationPageResponse(
                pageRows.stream().map(this::toItem).toList(),
                nextCursorCreatedAt,
                nextCursorId,
                hasMore
        );
    }

    @Override
    public NotificationUnreadCountResponse unreadCount(long recipientUserId) {
        return new NotificationUnreadCountResponse(notificationMapper.countUnread(recipientUserId));
    }

    @Override
    public void markRead(long recipientUserId, long notificationId) {
        Notification notification = notificationMapper.findById(notificationId);
        if (notification == null || !Long.valueOf(recipientUserId).equals(notification.getRecipientUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "通知不存在");
        }
        notificationMapper.markRead(notificationId, recipientUserId, LocalDateTime.now());
    }

    @Override
    public void markAllRead(long recipientUserId) {
        notificationMapper.markAllRead(recipientUserId, LocalDateTime.now());
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private NotificationItemResponse toItem(Notification notification) {
        return new NotificationItemResponse(
                String.valueOf(notification.getId()),
                notification.getType(),
                notification.getIsRead() != null && notification.getIsRead() == 1,
                notification.getCreatedAt(),
                String.valueOf(notification.getActorUserId()),
                notification.getEntityType(),
                notification.getEntityId() == null ? null : String.valueOf(notification.getEntityId()),
                notification.getSecondEntityType(),
                notification.getSecondEntityId() == null ? null : String.valueOf(notification.getSecondEntityId()),
                notification.getAggregateCount(),
                notification.getWindowStart(),
                notification.getWindowEnd()
        );
    }
}
