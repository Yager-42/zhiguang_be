package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class RelationFeedbackRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;

    public RelationFeedbackRecommendationConsumer(ObjectMapper objectMapper,
                                                  GorseClient gorseClient,
                                                  GorseProperties properties) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-relation-feedback-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, message)) {
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }
            RelationEvent event;
            try {
                event = objectMapper.readValue(payloadNode.asText(), RelationEvent.class);
            } catch (Exception ignored) {
                continue;
            }
            try {
                if ("FollowCreated".equals(event.type())) {
                    gorseClient.insertFeedback("follow", event.fromUserId(), String.valueOf(event.toUserId()));
                } else if ("FollowCanceled".equals(event.type())) {
                    gorseClient.insertFeedback("unfollow", event.fromUserId(), String.valueOf(event.toUserId()));
                }
            } catch (RuntimeException ignored) {
                return;
            }
        }
        acknowledgment.acknowledge();
    }
}
