package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterTopics;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.reconciliation.executor.GorseFeedbackReconciler.GorseFeedbackPayload;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class CounterFeedbackRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final ReconciliationService reconciliationService;

    public CounterFeedbackRecommendationConsumer(ObjectMapper objectMapper,
                                                 GorseClient gorseClient,
                                                 GorseProperties properties,
                                                 ReconciliationService reconciliationService) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.reconciliationService = reconciliationService;
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "recommendation-counter-feedback-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class);
        if ("knowpost".equals(event.getEntityType())
                && event.getDelta() > 0
                && ("like".equals(event.getMetric()) || "fav".equals(event.getMetric()))) {
            try {
                gorseClient.insertFeedback(event.getMetric(), event.getUserId(), event.getEntityId());
            } catch (RuntimeException ignored) {
                Long targetId = parseTargetId(event.getEntityId());
                if (targetId != null) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.GORSE_FEEDBACK,
                            ReconciliationTargetType.POST,
                            targetId,
                            objectMapper.writeValueAsString(new GorseFeedbackPayload(
                                    event.getMetric(),
                                    event.getUserId(),
                                    event.getEntityId()
                            ))
                    );
                }
            }
        }
        acknowledgment.acknowledge();
    }

    private Long parseTargetId(String entityId) {
        try {
            return Long.parseLong(entityId);
        } catch (Exception ignored) {
            return null;
        }
    }
}
