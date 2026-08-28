package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.service.CounterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentCounterConsumerTest {

    @Test
    void topLevelRoutesStableEventToPostCounter() throws Exception {
        CounterService counterService = mock(CounterService.class);
        CounterEventProducer producer = mock(CounterEventProducer.class);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event(CommentEventType.COMMENT_CREATED, 0L, 0L);
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentCounterConsumer consumer = new CommentCounterConsumer(
                eventReader, counterService, producer, mock(CommentMetrics.class));

        consumer.onMessage("message");

        verify(counterService).initializeCounts("comment", "101");
        ArgumentCaptor<CounterEvent> eventCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(producer).publishReliable(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventId()).isEqualTo("201:post-comment-count");
        assertThat(eventCaptor.getValue().getEntityType()).isEqualTo("knowpost");
        assertThat(eventCaptor.getValue().getEntityId()).isEqualTo("9");
    }

    @Test
    void replyRoutesStableEventToRootCounter() throws Exception {
        CounterEventProducer producer = mock(CounterEventProducer.class);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event(CommentEventType.COMMENT_CREATED, 51L, 51L);
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentCounterConsumer consumer = new CommentCounterConsumer(
                eventReader, mock(CounterService.class), producer, mock(CommentMetrics.class));

        consumer.onMessage("message");

        ArgumentCaptor<CounterEvent> eventCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(producer).publishReliable(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventId()).isEqualTo("201:root-reply-count");
        assertThat(eventCaptor.getValue().getEntityType()).isEqualTo("comment");
        assertThat(eventCaptor.getValue().getEntityId()).isEqualTo("51");
    }

    @Test
    void nonCreatedEventIsSkipped() throws Exception {
        CounterService counterService = mock(CounterService.class);
        CounterEventProducer producer = mock(CounterEventProducer.class);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event(CommentEventType.COMMENT_DELETED, 0L, 0L);
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentCounterConsumer consumer = new CommentCounterConsumer(
                eventReader, counterService, producer, mock(CommentMetrics.class));

        consumer.onMessage("message");

        verify(counterService, never()).initializeCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(producer, never()).publishReliable(org.mockito.ArgumentMatchers.any());
    }

    private CommentOutboxEvent event(CommentEventType type, Long rootId, Long parentId) {
        return new CommentOutboxEvent(201L, type, 101L, 9L, rootId, parentId, 7L,
                "client-1", null, LocalDateTime.of(2026, 8, 7, 10, 0));
    }

}
