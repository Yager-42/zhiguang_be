package com.tongji.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LikeNotificationBucket {
    private Long recipientUserId;
    private String entityType;
    private Long entityId;
    private Long windowStartEpochMillis;
    private Long windowEndEpochMillis;
    private Integer count;
    private Long latestActorUserId;
    private Long latestEventAt;
}
