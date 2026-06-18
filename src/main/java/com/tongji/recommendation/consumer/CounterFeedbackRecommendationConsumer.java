package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterTopics;
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

    public CounterFeedbackRecommendationConsumer(ObjectMapper objectMapper,
                                                 GorseClient gorseClient,
                                                 GorseProperties properties) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
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
            gorseClient.insertFeedback(event.getMetric(), event.getUserId(), event.getEntityId());
        }
        acknowledgment.acknowledge();
    }
}
