package com.tongji.notification.mapper;

import com.tongji.notification.model.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMapper {
    int insert(Notification notification);

    List<Notification> listByRecipient(@Param("recipientUserId") long recipientUserId,
                                       @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                       @Param("cursorId") Long cursorId,
                                       @Param("limit") int limit);

    int countUnread(@Param("recipientUserId") long recipientUserId);

    Notification findById(@Param("id") long id);

    int markRead(@Param("id") long id,
                 @Param("recipientUserId") long recipientUserId,
                 @Param("readAt") LocalDateTime readAt);

    int markAllRead(@Param("recipientUserId") long recipientUserId,
                    @Param("readAt") LocalDateTime readAt);
}
