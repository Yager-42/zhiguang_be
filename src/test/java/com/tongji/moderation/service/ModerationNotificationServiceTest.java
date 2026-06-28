package com.tongji.moderation.service;

import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.impl.ModerationNotificationServiceImpl;
import com.tongji.notification.model.Notification;
import com.tongji.notification.model.NotificationType;
import com.tongji.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ModerationNotificationServiceTest {

    @Test
    void approvedReportNotifiesAuthorAndReporterWithPlatformActor() {
        NotificationService notificationService = mock(NotificationService.class);
        ModerationProperties properties = new ModerationProperties();
        properties.getNotification().setPlatformActorUserId(999L);
        ModerationNotificationService service = new ModerationNotificationServiceImpl(notificationService, properties);
        ModerationReport report = ModerationReport.builder()
                .id(21L)
                .reporterUserId(7L)
                .targetOwnerUserId(8L)
                .targetType("comment")
                .targetId(301L)
                .build();

        service.notifyReportProcessed(report, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService, times(2)).create(captor.capture());
        List<Notification> notifications = captor.getAllValues();
        assertThat(notifications).extracting(Notification::getActorUserId).containsOnly(999L);
        assertThat(notifications).extracting(Notification::getType)
                .containsExactly(NotificationType.MODERATION_ACTION, NotificationType.REPORT_PROCESSED);
        assertThat(notifications).extracting(Notification::getEntityType).containsOnly("comment");
        assertThat(notifications.get(1).getRecipientUserId()).isEqualTo(7L);
        assertThat(notifications.get(1).getEventKey()).isEqualTo("moderation:report-processed:21");
    }

    @Test
    void ignoredReportOnlyNotifiesReporterAsProcessed() {
        NotificationService notificationService = mock(NotificationService.class);
        ModerationProperties properties = new ModerationProperties();
        properties.getNotification().setPlatformActorUserId(999L);
        ModerationNotificationService service = new ModerationNotificationServiceImpl(notificationService, properties);
        ModerationReport report = ModerationReport.builder()
                .id(22L)
                .reporterUserId(7L)
                .targetOwnerUserId(8L)
                .targetType("post")
                .targetId(101L)
                .build();

        service.notifyReportProcessed(report, false);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).create(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.REPORT_PROCESSED);
        assertThat(captor.getValue().getEntityType()).isEqualTo("knowpost");
        assertThat(captor.getValue().getEventKey()).isEqualTo("moderation:report-processed:22");
    }
}
