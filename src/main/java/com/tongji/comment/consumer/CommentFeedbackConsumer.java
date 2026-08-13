package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.event.CommentEventReader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import com.tongji.comment.metrics.CommentMetrics;

@Component
public class CommentFeedbackConsumer {
    private final CommentEventReader eventReader;
    private final CommentFeedbackProducer feedbackProducer;
    private final CommentMetrics metrics;

    public CommentFeedbackConsumer(CommentEventReader eventReader,
                                   CommentFeedbackProducer feedbackProducer,
                                   CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.feedbackProducer = feedbackProducer;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.feedback-group:comment-feedback-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        CommentOutboxEvent event = eventReader.read(message);
        if (event.eventType() != CommentEventType.COMMENT_CREATED) {
            return;
        }
        feedbackProducer.publishReliable(new CommentFeedbackEvent(String.valueOf(event.eventId()), event.occurredAt(),
                event.commentId(), event.postId(), event.rootId(), event.parentId(), event.creatorId(),
                CommentFeedbackEvent.COMMENT));
        metrics.sideEffect("feedback", "success");
    }

}
