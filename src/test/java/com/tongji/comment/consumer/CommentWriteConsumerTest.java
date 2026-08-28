package com.tongji.comment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentMaterializationService;
import com.tongji.outbox.OutboxTopics;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommentWriteConsumerTest {

    @Test
    void infrastructureRetryUsesBoundedExponentialBackoff() throws Exception {
        RetryableTopic retry = CommentWriteConsumer.class.getMethod("onMessage", String.class)
                .getAnnotation(RetryableTopic.class);

        assertThat(retry.attempts()).isEqualTo("${comment.kafka.write-retry-attempts:10}");
        assertThat(retry.backoff().delayExpression())
                .isEqualTo("${comment.kafka.write-retry-delay-ms:1000}");
        assertThat(retry.backoff().multiplierExpression())
                .isEqualTo("${comment.kafka.write-retry-multiplier:2}");
        assertThat(retry.backoff().maxDelayExpression())
                .isEqualTo("${comment.kafka.write-retry-max-delay-ms:10000}");
        assertThat(retry.exclude()).containsExactly(IllegalArgumentException.class);
        assertThat(retry.retryTopicSuffix()).isEqualTo("-comment-write-retry");
        assertThat(retry.dltTopicSuffix()).isEqualTo("-comment-write-dlt");
        assertThat(retry.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
        KafkaListener listener = CommentWriteConsumer.class.getMethod("onMessage", String.class)
                .getAnnotation(KafkaListener.class);
        assertThat(listener.topics()).containsExactly(OutboxTopics.CANAL_OUTBOX);
        assertThat(listener.groupId()).isEqualTo("${comment.kafka.write-group:comment-write-consumer}");
        assertThat(listener.containerFactory()).isEqualTo("commentWriteKafkaListenerContainerFactory");
    }

    @Test
    void writesCassandraBeforeFinalizerAndHasNoListenerTransaction() throws Exception {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        TextStorageService textStorageService = mock(TextStorageService.class);
        CommentMaterializationService finalizer = mock(CommentMaterializationService.class);
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                mock(CommentCanalEventReader.class), pendingMapper, textStorageService, finalizer,
                mock(CommentMetrics.class));
        CommentOutboxEvent event = event();
        PendingComment pending = PendingComment.builder()
                .pendingCommentId(101L).creatorId(7L).clientRequestId("client-1").status("pending").build();
        when(pendingMapper.findById(101L)).thenReturn(pending);

        consumer.handle(event);

        var ordered = org.mockito.Mockito.inOrder(textStorageService, finalizer);
        ordered.verify(textStorageService).saveCommentTextIdempotent(101L, "hello", event.occurredAt());
        ordered.verify(finalizer).finalizeMaterialization(event, pending);
        assertThat(CommentWriteConsumer.class.getMethod("onMessage", String.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void succeededReplaySkipsCassandraAndMysqlFinalizer() {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        TextStorageService textStorageService = mock(TextStorageService.class);
        CommentMaterializationService finalizer = mock(CommentMaterializationService.class);
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                mock(CommentCanalEventReader.class), pendingMapper, textStorageService, finalizer,
                mock(CommentMetrics.class));
        when(pendingMapper.findById(101L)).thenReturn(PendingComment.builder()
                .pendingCommentId(101L).status("succeeded").build());

        consumer.handle(event());

        verifyNoInteractions(textStorageService, finalizer);
    }

    @Test
    void cassandraFailureDoesNotEnterFinalizer() {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        TextStorageService textStorageService = mock(TextStorageService.class);
        CommentMaterializationService finalizer = mock(CommentMaterializationService.class);
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                mock(CommentCanalEventReader.class), pendingMapper, textStorageService, finalizer,
                mock(CommentMetrics.class));
        CommentOutboxEvent event = event();
        PendingComment pending = PendingComment.builder()
                .pendingCommentId(101L).creatorId(7L).clientRequestId("client-1").status("pending").build();
        when(pendingMapper.findById(101L)).thenReturn(pending);
        org.mockito.Mockito.doThrow(new IllegalStateException("cassandra down"))
                .when(textStorageService).saveCommentTextIdempotent(101L, "hello", event.occurredAt());

        assertThatThrownBy(() -> consumer.handle(event)).hasMessageContaining("cassandra down");
        verify(finalizer, never()).finalizeMaterialization(event, pending);
    }

    @Test
    void dltTransitionsOnlyPendingRowToFailed() {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        CommentMetrics metrics = mock(CommentMetrics.class);
        when(pendingMapper.updateStatusIfCurrent(101L, "failed", "pending")).thenReturn(1);
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                reader(), pendingMapper, mock(TextStorageService.class),
                mock(CommentMaterializationService.class), metrics);

        try {
            consumer.onDlt(envelope(event()));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        verify(metrics).dlt("failed_pending");
    }

    @Test
    void failedDltReplayIsIdempotent() throws Exception {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        CommentMetrics metrics = mock(CommentMetrics.class);
        when(pendingMapper.updateStatusIfCurrent(101L, "failed", "pending")).thenReturn(0);
        when(pendingMapper.findById(101L)).thenReturn(PendingComment.builder()
                .pendingCommentId(101L).status("failed").build());
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                reader(), pendingMapper, mock(TextStorageService.class),
                mock(CommentMaterializationService.class), metrics);

        consumer.onDlt(envelope(event()));

        verify(metrics).dlt("already_failed");
    }

    @Test
    void dltUpdateFailureIsObservableAndRetriable() throws Exception {
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        CommentMetrics metrics = mock(CommentMetrics.class);
        when(pendingMapper.updateStatusIfCurrent(101L, "failed", "pending")).thenReturn(0);
        when(pendingMapper.findById(101L)).thenReturn(PendingComment.builder()
                .pendingCommentId(101L).status("succeeded").build());
        CommentWriteConsumer consumer = new CommentWriteConsumer(
                reader(), pendingMapper, mock(TextStorageService.class),
                mock(CommentMaterializationService.class), metrics);
        String message = envelope(event());

        assertThatThrownBy(() -> consumer.onDlt(message)).hasMessageContaining("could not transition");
        verify(metrics).dlt("update_failure");
    }

    private CommentCanalEventReader reader() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new CommentCanalEventReader(objectMapper, new CommentEventReader(objectMapper));
    }

    private String envelope(CommentOutboxEvent event) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var root = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT");
        root.putArray("data").add(objectMapper.createObjectNode()
                .put("id", event.eventId())
                .put("aggregate_type", "comment")
                .put("aggregate_id", event.commentId())
                .put("type", event.eventType().name())
                .put("payload", objectMapper.writeValueAsString(event)));
        return objectMapper.writeValueAsString(root);
    }

    private CommentOutboxEvent event() {
        return new CommentOutboxEvent(
                201L,
                CommentEventType.COMMENT_WRITE_REQUESTED,
                CommentOutboxEvent.CURRENT_SCHEMA_VERSION,
                101L,
                9L,
                0L,
                0L,
                7L,
                "client-1",
                "hello",
                LocalDateTime.of(2026, 8, 7, 10, 0)
        );
    }
}
