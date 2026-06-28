package com.tongji.moderation.service.impl;

import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.model.ModerationTargetType;
import com.tongji.moderation.service.ModerationNotificationService;
import com.tongji.notification.model.Notification;
import com.tongji.notification.model.NotificationType;
import com.tongji.notification.service.NotificationService;
import org.springframework.stereotype.Service;

@Service
public class ModerationNotificationServiceImpl implements ModerationNotificationService {
    private final NotificationService notificationService;
    private final ModerationProperties properties;

    public ModerationNotificationServiceImpl(NotificationService notificationService, ModerationProperties properties) {
        this.notificationService = notificationService;
        this.properties = properties;
    }

    @Override
    public void notifyReportProcessed(ModerationReport report, boolean contentActionApplied) {
        long actorUserId = properties.getNotification().getPlatformActorUserId();
        if (contentActionApplied) {
            notificationService.create(Notification.builder()
                    .recipientUserId(report.getTargetOwnerUserId())
                    .actorUserId(actorUserId)
                    .type(NotificationType.MODERATION_ACTION)
                    .entityType(toNotificationEntityType(report.getTargetType()))
                    .entityId(report.getTargetId())
                    .secondEntityType("report")
                    .secondEntityId(report.getId())
                    .eventKey("moderation:action:" + report.getId())
                    .build());
        }
        notificationService.create(Notification.builder()
                .recipientUserId(report.getReporterUserId())
                .actorUserId(actorUserId)
                .type(NotificationType.REPORT_PROCESSED)
                .entityType(toNotificationEntityType(report.getTargetType()))
                .entityId(report.getTargetId())
                .secondEntityType("report")
                .secondEntityId(report.getId())
                .eventKey("moderation:report-processed:" + report.getId())
                .build());
    }

    private String toNotificationEntityType(String targetType) {
        if (ModerationTargetType.POST.equals(targetType)) {
            return "knowpost";
        }
        return targetType;
    }
}
