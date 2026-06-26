package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.notification.service.NotificationCommandService;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.outbox.OutboxTopics;
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
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, message)) {
            JsonNode payloadNode = row.get("payload");
            JsonNode idNode = row.get("id");
            if (payloadNode == null || idNode == null) {
                continue;
            }
            RelationEvent event;
            try {
                event = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
            } catch (Exception ignored) {
                continue;
            }
            if (!"FollowCreated".equals(event.type())
                    || event.fromUserId() == null
                    || event.toUserId() == null) {
                continue;
            }
            notificationCommandService.createFollowNotification(
                    event.fromUserId(),
                    event.toUserId(),
                    "follow:outbox:" + idNode.asText()
            );
        }
        acknowledgment.acknowledge();
    }
}
