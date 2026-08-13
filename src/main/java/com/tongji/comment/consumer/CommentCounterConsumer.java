package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.event.CommentEventReader;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.service.CounterService;
import com.tongji.comment.metrics.CommentMetrics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class CommentCounterConsumer {
    private final CommentEventReader eventReader;
    private final CounterService counterService;
    private final CounterEventProducer counterEventProducer;
    private final CommentMetrics metrics;

    public CommentCounterConsumer(CommentEventReader eventReader,
                                  CounterService counterService,
                                  CounterEventProducer counterEventProducer,
                                  CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.counterService = counterService;
        this.counterEventProducer = counterEventProducer;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.counter-group:comment-counter-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        CommentOutboxEvent event = eventReader.read(message);
        if (event.eventType() != CommentEventType.COMMENT_CREATED) {
            return;
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
