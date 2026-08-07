package com.tongji.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentMaterializationService;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentMaterializationServiceTest {
    private CommentMapper commentMapper;
    private PendingCommentMapper pendingMapper;
    private CommentOutboxMapper outboxMapper;
    private IdService idService;
    private ApplicationEventPublisher eventPublisher;
    private CommentMaterializationService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        pendingMapper = mock(PendingCommentMapper.class);
        outboxMapper = mock(CommentOutboxMapper.class);
        idService = mock(IdService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new CommentMaterializationService(commentMapper, pendingMapper, outboxMapper, idService,
                new ObjectMapper().findAndRegisterModules(), eventPublisher, mock(CommentMetrics.class));
    }

    @Test
    void insertsCommentTransitionsPendingAndCreatesAtomicEvent() {
        CommentOutboxEvent event = event();
        PendingComment pending = pending("pending");
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(1);
        when(pendingMapper.updateStatusIfCurrent(101L, "succeeded", "pending")).thenReturn(1);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(301L);

        service.finalizeMaterialization(event, pending);

        verify(commentMapper).insertIgnore(any(Comment.class));
        verify(pendingMapper, never()).findById(101L);
        verify(pendingMapper).updateStatusIfCurrent(101L, "succeeded", "pending");
        ArgumentCaptor<CommentOutbox> outbox = ArgumentCaptor.forClass(CommentOutbox.class);
        verify(outboxMapper).insertIgnore(outbox.capture());
        assertThat(outbox.getValue().getEventType()).isEqualTo("COMMENT_CREATED");
        assertThat(outbox.getValue().getAggregateId()).isEqualTo(101L);
        verify(eventPublisher).publishEvent(any(CommentMutationEvent.class));
    }

    @Test
    void duplicateCommentValidatesCanonicalRowWithoutSecondInsertEffect() {
        CommentOutboxEvent event = event();
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(0);
        when(commentMapper.findById(101L)).thenReturn(Comment.builder()
                .commentId(101L).postId(9L).rootId(0L).parentId(0L).creatorId(7L)
                .clientRequestId("client-1").createTime(event.occurredAt()).build());
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(302L);

        service.finalizeMaterialization(event, pending("succeeded"));

        verify(pendingMapper, never()).updateStatusIfCurrent(any(), any(), any());
        verify(outboxMapper).insertIgnore(any(CommentOutbox.class));
    }

    @Test
    void rejectsEventThatDoesNotMatchCanonicalPendingRow() {
        PendingComment mismatched = PendingComment.builder()
                .pendingCommentId(101L).creatorId(8L).clientRequestId("client-1").status("pending").build();

        assertThatThrownBy(() -> service.finalizeMaterialization(event(), mismatched))
                .hasMessageContaining("canonical pending");

        verify(commentMapper, never()).insertIgnore(any(Comment.class));
    }

    @Test
    void rereadsPendingOnlyWhenConditionalTransitionLosesRace() {
        CommentOutboxEvent event = event();
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(1);
        when(pendingMapper.updateStatusIfCurrent(101L, "succeeded", "pending")).thenReturn(0);
        when(pendingMapper.findById(101L)).thenReturn(pending("succeeded"));
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(303L);

        service.finalizeMaterialization(event, pending("pending"));

        verify(pendingMapper).findById(101L);
        verify(outboxMapper).insertIgnore(any(CommentOutbox.class));
    }

    private CommentOutboxEvent event() {
        return new CommentOutboxEvent(201L, CommentEventType.COMMENT_WRITE_REQUESTED, 101L, 9L,
                0L, 0L, 7L, "client-1", "hello", LocalDateTime.of(2026, 8, 7, 10, 0));
    }

    private PendingComment pending(String status) {
        return PendingComment.builder().pendingCommentId(101L).postId(9L).creatorId(7L)
                .clientRequestId("client-1").status(status)
                .createTime(LocalDateTime.of(2026, 8, 7, 9, 59)).build();
    }
}
