package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxPayload;
import com.tongji.outbox.OutboxTopics;
import com.tongji.profile.event.UserProfileUpdatedEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
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
        for (OutboxEvent event : OutboxMessageReader.read(objectMapper, message)) {
            OutboxPayload payload = event.parsePayload(objectMapper).orElse(null);
            if (payload == null || !"user_profile_updated".equals(payload.text("eventType"))) {
                continue;
            }
            UserProfileUpdatedEvent user = payload.fieldAs("user", UserProfileUpdatedEvent.class);
            if (user == null) {
                continue;
            }
            try {
                gorseClient.upsertUser(user);
            } catch (RuntimeException ignored) {
                return;
            }
        }
        acknowledgment.acknowledge();
    }

}
