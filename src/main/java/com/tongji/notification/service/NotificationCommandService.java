package com.tongji.notification.service;

import com.tongji.notification.model.LikeNotificationBucket;

public interface NotificationCommandService {
    void createFollowNotification(long actorUserId, long recipientUserId, String eventKey);

    void createCommentNotification(long actorUserId,
                                   long recipientUserId,
                                   long postId,
                                   long commentId,
                                   String eventKey);

    void createLikeNotification(LikeNotificationBucket bucket);
}
