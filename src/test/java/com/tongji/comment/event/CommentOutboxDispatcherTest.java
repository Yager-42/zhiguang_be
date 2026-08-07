package com.tongji.comment.event;

import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.CommentOutboxRetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class CommentOutboxDispatcherTest {

    @Test
    void marksSuccessfulAndFailedSendsAsSeparateSubsets() {
        CommentOutboxMapper mapper = mock(CommentOutboxMapper.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CommentMetrics metrics = mock(CommentMetrics.class);
        CommentOutbox succeeded = outbox(11L, CommentEventType.COMMENT_WRITE_REQUESTED, 0);
        CommentOutbox failed = outbox(12L, CommentEventType.COMMENT_CREATED, 2);

        when(mapper.claimReady(anyString(), any(), any(), any(Integer.class))).thenReturn(2);
        when(mapper.findClaimed(anyString())).thenReturn(List.of(succeeded, failed));
        when(kafkaTemplate.send("comment-write", "101", succeeded.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafkaTemplate.send("comment-events", "102", failed.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        CommentOutboxDispatcher dispatcher = new CommentOutboxDispatcher(
                mapper, kafkaTemplate, Runnable::run, "comment-write", "comment-events", 500, 30, metrics);
        dispatcher.dispatchReady();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> publishedCaptor = ArgumentCaptor.forClass(List.class);
        verify(mapper).markPublishedBatch(publishedCaptor.capture(), anyString(), any());
        assertThat(publishedCaptor.getValue()).containsExactly(11L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommentOutboxRetry>> retryCaptor = ArgumentCaptor.forClass(List.class);
        verify(mapper).markRetryBatch(retryCaptor.capture(), anyString());
        assertThat(retryCaptor.getValue()).singleElement().satisfies(retry -> {
            assertThat(retry.eventId()).isEqualTo(12L);
            assertThat(retry.retryCount()).isEqualTo(3);
            assertThat(retry.lastError()).isEqualTo("broker unavailable");
        });
        verify(metrics, times(1)).outbox("published", "COMMENT_WRITE_REQUESTED", 1);
        verify(metrics, times(1)).outbox("send_failure", "COMMENT_CREATED", 1);
        verify(metrics, times(1)).outbox("retry", "COMMENT_CREATED", 1);
    }

    private CommentOutbox outbox(long eventId, CommentEventType eventType, int retryCount) {
        return CommentOutbox.builder()
                .eventId(eventId)
                .eventType(eventType.name())
                .aggregateId(eventId + 90)
                .payload("{\"eventId\":" + eventId + "}")
                .retryCount(retryCount)
                .build();
    }
}
