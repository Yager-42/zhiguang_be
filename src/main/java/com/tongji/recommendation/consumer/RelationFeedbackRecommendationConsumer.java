package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.reconciliation.executor.GorseFeedbackReconciler.GorseFeedbackPayload;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.event.RelationEvent;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class RelationFeedbackRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final ReconciliationService reconciliationService;

    public RelationFeedbackRecommendationConsumer(ObjectMapper objectMapper,
                                                  GorseClient gorseClient,
                                                  GorseProperties properties,
                                                  ReconciliationService reconciliationService) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.reconciliationService = reconciliationService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-relation-feedback-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        for (OutboxEvent envelope : OutboxMessageReader.read(objectMapper, message)) {
            RelationEvent event = envelope.payloadAs(objectMapper, RelationEvent.class).orElse(null);
            if (event == null) {
                continue;
            }
            try {
                String feedbackType = null;
                if ("FollowCreated".equals(event.type())) {
                    feedbackType = "follow";
                    gorseClient.insertFeedback(feedbackType, event.fromUserId(), String.valueOf(event.toUserId()));
                } else if ("FollowCanceled".equals(event.type())) {
                    feedbackType = "unfollow";
                    gorseClient.insertFeedback(feedbackType, event.fromUserId(), String.valueOf(event.toUserId()));
                }
            } catch (RuntimeException ignored) {
                String feedbackType = "FollowCreated".equals(event.type()) ? "follow"
                        : "FollowCanceled".equals(event.type()) ? "unfollow" : null;
                if (event.toUserId() != null && event.fromUserId() != null && feedbackType != null) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.GORSE_FEEDBACK,
                            ReconciliationTargetType.USER,
                            event.toUserId(),
                            writePayload(new GorseFeedbackPayload(
                                    feedbackType,
                                    event.fromUserId(),
                                    String.valueOf(event.toUserId())
                            ))
                    );
                }
            }
        }
        acknowledgment.acknowledge();
    }

    private String writePayload(GorseFeedbackPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize gorse feedback payload", e);
        }
    }
}
