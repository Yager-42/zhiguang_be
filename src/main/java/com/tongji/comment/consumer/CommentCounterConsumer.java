package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.service.CounterService;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 消费共享评论创建事实并可靠派生评论计数事件。
 *
 * @since 2026-08-28
 */
@Component
public class CommentCounterConsumer {
    private final CommentCanalEventReader eventReader;
    private final CounterService counterService;
    private final CounterEventProducer counterEventProducer;
    private final CommentMetrics metrics;

    public CommentCounterConsumer(CommentCanalEventReader eventReader,
                                  CounterService counterService,
                                  CounterEventProducer counterEventProducer,
                                  CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.counterService = counterService;
        this.counterEventProducer = counterEventProducer;
        this.metrics = metrics;
    }

    /**
     * 处理评论创建事实；稳定事件 ID 保证下游重复投递可幂等合并。
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
            retryTopicSuffix = "-comment-counter-retry",
            dltTopicSuffix = "-comment-counter-dlt",
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${comment.kafka.counter-group:comment-counter-effects}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        for (CommentOutboxEvent event : eventReader.readMutations(message)) {
            if (event.eventType() != CommentEventType.COMMENT_CREATED) {
                continue;
            }
            counterService.initializeCounts("comment", String.valueOf(event.commentId()));
            boolean reply = event.parentId() != null && event.parentId() > 0;
            String entityType = reply ? "comment" : "knowpost";
            String entityId = String.valueOf(reply ? event.rootId() : event.postId());
            String effect = reply ? "root-reply-count" : "post-comment-count";
            long occurredAt = event.occurredAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            CounterEvent counterEvent = new CounterEvent(event.eventId() + ":" + effect, occurredAt,
                    entityType, entityId, "comment", CounterSchema.IDX_COMMENT, event.creatorId(), 1);
            counterEventProducer.publishReliable(counterEvent);
            metrics.sideEffect("counter", "success");
        }
    }

}
