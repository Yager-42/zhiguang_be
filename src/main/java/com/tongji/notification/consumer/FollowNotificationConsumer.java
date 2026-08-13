package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.notification.service.NotificationCommandService;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxTopics;
import com.tongji.relation.event.RelationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class FollowNotificationConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationCommandService notificationCommandService;

    public FollowNotificationConsumer(ObjectMapper objectMapper, NotificationCommandService notificationCommandService) {
        this.objectMapper = objectMapper;
        this.notificationCommandService = notificationCommandService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "notification-follow-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        for (OutboxEvent envelope : OutboxMessageReader.read(objectMapper, message)) {
            if (envelope.id() == null) {
                continue;
            }
            RelationEvent event = envelope.payloadAs(objectMapper, RelationEvent.class).orElse(null);
            if (event == null
                    || !"FollowCreated".equals(event.type())
                    || event.fromUserId() == null
                    || event.toUserId() == null) {
                continue;
            }
            notificationCommandService.createFollowNotification(
                    event.fromUserId(),
                    event.toUserId(),
                    "follow:outbox:" + envelope.id()
            );
        }
        acknowledgment.acknowledge();
    }
}
