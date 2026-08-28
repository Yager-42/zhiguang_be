package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * 消费共享评论创建事实并可靠派生评论反馈事件。
 *
 * @since 2026-08-28
 */
@Component
public class CommentFeedbackConsumer {
    private final CommentCanalEventReader eventReader;
    private final CommentFeedbackProducer feedbackProducer;
    private final CommentMetrics metrics;

    public CommentFeedbackConsumer(CommentCanalEventReader eventReader,
                                   CommentFeedbackProducer feedbackProducer,
                                   CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.feedbackProducer = feedbackProducer;
        this.metrics = metrics;
    }

    /**
     * 处理评论创建事实；下游 Kafka 临时故障通过独立 Retry Topic 重试。
     *
     * @param message Canal Outbox envelope JSON
     */
    @RetryableTopic(
            attempts = "${comment.kafka.effect-retry-attempts:10}",
            backoff = @Backoff(
                    delayExpression = "${comment.kafka.effect-retry-delay-ms:1000}",
                    multiplierExpression = "${comment.kafka.effect-retry-multiplier:2}",
                    maxDelayExpression = "${comment.kafka.effect-retry-max-delay-ms:10000}"
            ),
            retryTopicSuffix = "-comment-feedback-retry",
            dltTopicSuffix = "-comment-feedback-dlt",
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${comment.kafka.feedback-group:comment-feedback-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        for (CommentOutboxEvent event : eventReader.readMutations(message)) {
            if (event.eventType() != CommentEventType.COMMENT_CREATED) {
                continue;
            }
            feedbackProducer.publishReliable(new CommentFeedbackEvent(
                    String.valueOf(event.eventId()),
                    event.occurredAt(),
                    event.commentId(),
                    event.postId(),
                    event.rootId(),
                    event.parentId(),
                    event.creatorId(),
                    CommentFeedbackEvent.COMMENT
            ));
            metrics.sideEffect("feedback", "success");
        }
    }

}
