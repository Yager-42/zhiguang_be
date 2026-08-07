package com.tongji.comment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentMaterializationService;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.storage.text.TextStorageService;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CommentWriteConsumer {
    private final ObjectMapper objectMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final TextStorageService textStorageService;
    private final CommentMaterializationService materializationService;
    private final CommentMetrics metrics;

    public CommentWriteConsumer(ObjectMapper objectMapper,
                                PendingCommentMapper pendingCommentMapper,
                                TextStorageService textStorageService,
                                CommentMaterializationService materializationService,
                                CommentMetrics metrics) {
        this.objectMapper = objectMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.textStorageService = textStorageService;
        this.materializationService = materializationService;
        this.metrics = metrics;
    }

    @RetryableTopic(
            attempts = "${comment.kafka.write-retry-attempts:10}",
            backoff = @Backoff(
                    delayExpression = "${comment.kafka.write-retry-delay-ms:1000}",
                    multiplierExpression = "${comment.kafka.write-retry-multiplier:2}",
                    maxDelayExpression = "${comment.kafka.write-retry-max-delay-ms:10000}"
            ),
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(
            topics = "${comment.kafka.write-topic:comment-write}",
            groupId = "${comment.kafka.write-group:comment-write-consumer}",
            containerFactory = "commentWriteKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        handle(read(message));
    }

    void handle(CommentOutboxEvent event) {
        if (event.eventType() != CommentEventType.COMMENT_WRITE_REQUESTED) {
            throw new IllegalArgumentException("unexpected event type on comment-write topic");
        }
        PendingComment pending = pendingCommentMapper.findById(event.commentId());
        if (pending == null) {
            throw new IllegalStateException("missing pending comment");
        }
        if ("succeeded".equals(pending.getStatus())) {
            return;
        }
        if (!event.commentId().equals(pending.getPendingCommentId())
                || !event.creatorId().equals(pending.getCreatorId())
                || !event.clientRequestId().equals(pending.getClientRequestId())
                || "failed".equals(pending.getStatus())) {
            throw new IllegalStateException("comment event does not match pending row");
        }
        long cassandraStart = System.nanoTime();
        try {
            textStorageService.saveCommentTextIdempotent(event.commentId(), event.body(), event.occurredAt());
            metrics.materialization("cassandra", "success", Duration.ofNanos(System.nanoTime() - cassandraStart));
        } catch (RuntimeException exception) {
            metrics.materialization("cassandra", "failure", Duration.ofNanos(System.nanoTime() - cassandraStart));
            throw exception;
        }
        long finalizerStart = System.nanoTime();
        try {
            materializationService.finalizeMaterialization(event);
            metrics.materialization("mysql_finalizer", "success",
                    Duration.ofNanos(System.nanoTime() - finalizerStart));
        } catch (RuntimeException exception) {
            metrics.materialization("mysql_finalizer", "failure",
                    Duration.ofNanos(System.nanoTime() - finalizerStart));
            throw exception;
        }
    }

    @DltHandler
    public void onDlt(String message) {
        CommentOutboxEvent event = read(message);
        int updated = pendingCommentMapper.updateStatusIfCurrent(event.commentId(), "failed", "pending");
        if (updated == 1) {
            metrics.dlt("failed_pending");
            return;
        }
        PendingComment current = pendingCommentMapper.findById(event.commentId());
        if (current != null && "failed".equals(current.getStatus())) {
            metrics.dlt("already_failed");
            return;
        }
        metrics.dlt("update_failure");
        throw new IllegalStateException("comment DLT could not transition pending row to failed");
    }

    private CommentOutboxEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentOutboxEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment outbox event", exception);
        }
    }
}
