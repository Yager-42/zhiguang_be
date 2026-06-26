package com.tongji.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    private Long id;
    private Long recipientUserId;
    private Long actorUserId;
    private String type;
    private String entityType;
    private Long entityId;
    private String secondEntityType;
    private Long secondEntityId;
    private String eventKey;
    private Integer aggregateCount;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private Integer isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
