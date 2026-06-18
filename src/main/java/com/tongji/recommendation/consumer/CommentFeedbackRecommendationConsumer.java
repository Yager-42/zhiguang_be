package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class CommentFeedbackRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;

    public CommentFeedbackRecommendationConsumer(ObjectMapper objectMapper,
                                                 GorseClient gorseClient,
                                                 GorseProperties properties) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${comment.kafka.feedback-topic:comment-feedback}",
            groupId = "recommendation-comment-feedback-consumer"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        CommentFeedbackEvent event = objectMapper.readValue(message, CommentFeedbackEvent.class);
        if (CommentFeedbackEvent.COMMENT.equals(event.action()) && event.postId() != null) {
            gorseClient.insertFeedback("comment", event.creatorId(), String.valueOf(event.postId()));
        }
        acknowledgment.acknowledge();
    }
}
