package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentFeedbackProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public CommentFeedbackProducer(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${comment.kafka.feedback-topic:comment-feedback}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(CommentFeedbackEvent event) {
        try {
            kafkaTemplate.send(topic, String.valueOf(event.commentId()), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException exception) {
            // Feedback events are best-effort and must not fail the primary comment flow.
        }
    }
}
