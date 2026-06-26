package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.notification.service.NotificationCommandService;
import com.tongji.relation.event.RelationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class FollowNotificationConsumerTest {

    @Test
    void followCreatedCreatesNotification() throws Exception {
        NotificationCommandService commandService = mock(NotificationCommandService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        FollowNotificationConsumer consumer = new FollowNotificationConsumer(new ObjectMapper(), commandService);

        consumer.onMessage(canalMessage(77L, new RelationEvent("FollowCreated", 7L, 9L, 123L)), acknowledgment);

        verify(commandService).createFollowNotification(7L, 9L, "follow:outbox:77");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void nonFollowCreatedIsIgnored() throws Exception {
        NotificationCommandService commandService = mock(NotificationCommandService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        FollowNotificationConsumer consumer = new FollowNotificationConsumer(new ObjectMapper(), commandService);

        consumer.onMessage(canalMessage(77L, new RelationEvent("FollowCanceled", 7L, 9L, 123L)), acknowledgment);

        verify(acknowledgment).acknowledge();
        verifyNoMoreInteractions(commandService);
    }

    private String canalMessage(long outboxId, RelationEvent event) throws Exception {
        String payload = new ObjectMapper().writeValueAsString(event).replace("\"", "\\\"");
        return """
                {"table":"outbox","type":"INSERT","data":[{"id":"%d","payload":"%s"}]}
                """.formatted(outboxId, payload);
    }
}
