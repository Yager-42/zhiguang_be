package com.tongji.comment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentOutboxEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import com.tongji.comment.metrics.CommentMetrics;

@Component
public class CommentFeedbackConsumer {
    private final ObjectMapper objectMapper;
    private final CommentFeedbackProducer feedbackProducer;
    private final CommentMetrics metrics;

    public CommentFeedbackConsumer(ObjectMapper objectMapper,
                                   CommentFeedbackProducer feedbackProducer,
                                   CommentMetrics metrics) {
        this.objectMapper = objectMapper;
        this.feedbackProducer = feedbackProducer;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.feedback-group:comment-feedback-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        CommentOutboxEvent event = read(message);
        if (event.eventType() != CommentEventType.COMMENT_CREATED) {
            return;
        }
        feedbackProducer.publishReliable(new CommentFeedbackEvent(String.valueOf(event.eventId()), event.occurredAt(),
                event.commentId(), event.postId(), event.rootId(), event.parentId(), event.creatorId(),
                CommentFeedbackEvent.COMMENT));
        metrics.sideEffect("feedback", "success");
    }

    private CommentOutboxEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentOutboxEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment feedback event", exception);
        }
    }
}
