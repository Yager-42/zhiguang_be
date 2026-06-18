package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.profile.event.UserProfileUpdatedEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class UserProfileRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;

    public UserProfileRecommendationConsumer(ObjectMapper objectMapper,
                                             GorseClient gorseClient,
                                             GorseProperties properties) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-user-profile-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, message)) {
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }
            UserProfileUpdatedEvent event;
            try {
                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                if (!"user_profile_updated".equals(text(payload.get("eventType")))) {
                    continue;
                }
                JsonNode userNode = payload.get("user");
                if (userNode == null || userNode.isNull()) {
                    continue;
                }
                event = objectMapper.treeToValue(userNode, UserProfileUpdatedEvent.class);
            } catch (Exception ignored) {
                continue;
            }
            try {
                gorseClient.upsertUser(event);
            } catch (RuntimeException ignored) {
                return;
            }
        }
        acknowledgment.acknowledge();
    }

    private String text(JsonNode node) {
        return node == null ? null : node.asText();
    }
}
