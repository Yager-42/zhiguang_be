package com.tongji.comment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentEventWriterTest {
    private CommentOutboxMapper outboxMapper;
    private IdService idService;
    private ApplicationEventPublisher eventPublisher;
    private CommentEventWriter writer;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(CommentOutboxMapper.class);
        idService = mock(IdService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        writer = new CommentEventWriter(outboxMapper, idService,
                new ObjectMapper().findAndRegisterModules(), eventPublisher);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(501L);
    }

    @Test
    void writeRequestedPersistsStrictOutboxWithoutLocalMutationEvent() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 7, 10, 0);

        long eventId = writer.writeRequested(new CommentWriteRequest(
                101L, 9L, 0L, 0L, 7L, "client-1", "hello", occurredAt));

        assertThat(eventId).isEqualTo(501L);
        ArgumentCaptor<CommentOutbox> row = ArgumentCaptor.forClass(CommentOutbox.class);
        verify(outboxMapper).insert(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("COMMENT_WRITE_REQUESTED");
        assertThat(row.getValue().getAggregateId()).isEqualTo(101L);
        assertThat(row.getValue().getPayload()).contains("\"eventId\":501", "\"body\":\"hello\"");
        verify(outboxMapper, never()).insertIgnore(any(CommentOutbox.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createdEventReusesOriginalOccurrenceTimeAndPublishesLocalMutation() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 7, 10, 0);
        CommentOutboxEvent source = new CommentOutboxEvent(201L, CommentEventType.COMMENT_WRITE_REQUESTED,
                101L, 9L, 0L, 0L, 7L, "client-1", "hello", occurredAt);

        writer.createdFrom(source);

        ArgumentCaptor<CommentOutbox> row = ArgumentCaptor.forClass(CommentOutbox.class);
        verify(outboxMapper).insertIgnore(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("COMMENT_CREATED");
        assertThat(read(row.getValue().getPayload()).occurredAt()).isEqualTo(occurredAt);
        ArgumentCaptor<CommentMutationEvent> local = ArgumentCaptor.forClass(CommentMutationEvent.class);
        verify(eventPublisher).publishEvent(local.capture());
        assertThat(local.getValue().eventId()).isEqualTo(501L);
        assertThat(local.getValue().eventType()).isEqualTo(CommentEventType.COMMENT_CREATED);
    }

    @Test
    void deleteAndModerationUseReliableOutboxAndLocalAfterCommitSignal() {
        Comment comment = Comment.builder().commentId(101L).postId(9L).rootId(11L).parentId(11L)
                .creatorId(7L).clientRequestId("client-1").build();

        writer.deleted(comment);

        verify(outboxMapper).insertIgnore(org.mockito.ArgumentMatchers.argThat(row ->
                "COMMENT_DELETED".equals(row.getEventType()) && row.getAggregateId().equals(101L)));
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat((Object event) ->
                event instanceof CommentMutationEvent mutation
                        && mutation.eventType() == CommentEventType.COMMENT_DELETED
                        && mutation.rootId() == 11L));
    }
    private CommentOutboxEvent read(String payload) {
        try {
            return new ObjectMapper().findAndRegisterModules().readValue(payload, CommentOutboxEvent.class);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

}
