package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentMaterializationService;
import com.tongji.outbox.OutboxTopics;
import com.tongji.storage.text.TextStorageService;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 从共享 Canal Outbox 总线消费评论写请求，并在 Listener 线程直接完成关键物化。
 *
 * <p>临时故障进入评论专用 Retry Topic；畸形目标事件直接进入 DLT，DLT 只终止仍为 pending 的请求。</p>
 *
 * @since 2026-08-28
 */
@Component
public class CommentWriteConsumer {
    private final CommentCanalEventReader eventReader;
    private final PendingCommentMapper pendingCommentMapper;
    private final TextStorageService textStorageService;
    private final CommentMaterializationService materializationService;
    private final CommentMetrics metrics;

    public CommentWriteConsumer(CommentCanalEventReader eventReader,
                                PendingCommentMapper pendingCommentMapper,
                                TextStorageService textStorageService,
                                CommentMaterializationService materializationService,
                                CommentMetrics metrics) {
        this.eventReader = eventReader;
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
            retryTopicSuffix = "-comment-write-retry",
            dltTopicSuffix = "-comment-write-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(
            topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${comment.kafka.write-group:comment-write-consumer}",
            containerFactory = "commentWriteKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        for (CommentOutboxEvent event : eventReader.readRequested(message)) {
            handle(event);
        }
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
            materializationService.finalizeMaterialization(event, pending);
            metrics.materialization("mysql_finalizer", "success",
                    Duration.ofNanos(System.nanoTime() - finalizerStart));
        } catch (RuntimeException exception) {
            metrics.materialization("mysql_finalizer", "failure",
                    Duration.ofNanos(System.nanoTime() - finalizerStart));
            throw exception;
        }
    }

    /**
     * 将 DLT 中仍为 pending 的评论请求推进为 failed。
     *
     * @param message 原始 Canal Outbox envelope JSON
     */
    @DltHandler
    public void onDlt(String message) {
        for (CommentOutboxEvent event : eventReader.readRequested(message)) {
            failPending(event);
        }
    }

    private void failPending(CommentOutboxEvent event) {
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

}
