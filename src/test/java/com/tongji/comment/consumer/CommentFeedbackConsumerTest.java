package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentFeedbackConsumerTest {

    @Test
    void createdEventKeepsStableEventIdentity() throws Exception {
        CommentFeedbackProducer producer = mock(CommentFeedbackProducer.class);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event();
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentFeedbackConsumer consumer = new CommentFeedbackConsumer(
                eventReader, producer, mock(CommentMetrics.class));

        consumer.onMessage("message");

        ArgumentCaptor<CommentFeedbackEvent> eventCaptor = ArgumentCaptor.forClass(CommentFeedbackEvent.class);
        verify(producer).publishReliable(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId()).isEqualTo("201");
        assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 7, 10, 0));
    }

    @Test
    void reliablePublishFailureEscapesForKafkaRetry() throws Exception {
        CommentFeedbackProducer producer = mock(CommentFeedbackProducer.class);
        doThrow(new IllegalStateException("kafka unavailable")).when(producer)
                .publishReliable(org.mockito.ArgumentMatchers.any());
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        when(eventReader.readMutations("message")).thenReturn(List.of(event()));
        CommentFeedbackConsumer consumer = new CommentFeedbackConsumer(
                eventReader, producer, mock(CommentMetrics.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage("message"))
                .hasMessageContaining("kafka unavailable");
    }

    private CommentOutboxEvent event() {
        return new CommentOutboxEvent(201L, CommentEventType.COMMENT_CREATED,
                101L, 9L, 0L, 0L, 7L, "client-1", null,
                LocalDateTime.of(2026, 8, 7, 10, 0));
    }
}
