package com.tongji.comment.service;

import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentEventWriter;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentMaterializationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentMaterializationServiceTest {
    private CommentMapper commentMapper;
    private PendingCommentMapper pendingMapper;
    private CommentEventWriter eventWriter;
    private CommentMaterializationService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        pendingMapper = mock(PendingCommentMapper.class);
        eventWriter = mock(CommentEventWriter.class);
        service = new CommentMaterializationService(
                commentMapper, pendingMapper, eventWriter, mock(CommentMetrics.class));
    }

    @Test
    void insertsCommentTransitionsPendingAndCreatesAtomicEvent() {
        CommentOutboxEvent event = event();
        PendingComment pending = pending("pending");
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(1);
        when(pendingMapper.updateStatusIfCurrent(101L, "succeeded", "pending")).thenReturn(1);

        service.finalizeMaterialization(event, pending);

        verify(commentMapper).insertIgnore(any(Comment.class));
        verify(pendingMapper, never()).findById(101L);
        verify(pendingMapper).updateStatusIfCurrent(101L, "succeeded", "pending");
        verify(eventWriter).createdFrom(event);
    }

    @Test
    void duplicateCommentValidatesCanonicalRowWithoutSecondInsertEffect() {
        CommentOutboxEvent event = event();
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(0);
        when(commentMapper.findById(101L)).thenReturn(Comment.builder()
                .commentId(101L).postId(9L).rootId(0L).parentId(0L).creatorId(7L)
                .clientRequestId("client-1").createTime(event.occurredAt()).build());

        service.finalizeMaterialization(event, pending("succeeded"));

        verify(pendingMapper, never()).updateStatusIfCurrent(any(), any(), any());
        verify(eventWriter).createdFrom(event);
    }

    @Test
    void rejectsEventThatDoesNotMatchCanonicalPendingRow() {
        PendingComment mismatched = PendingComment.builder()
                .pendingCommentId(101L).creatorId(8L).clientRequestId("client-1").status("pending").build();

        assertThatThrownBy(() -> service.finalizeMaterialization(event(), mismatched))
                .hasMessageContaining("canonical pending");

        verify(commentMapper, never()).insertIgnore(any(Comment.class));
        verify(eventWriter, never()).createdFrom(any());
    }

    @Test
    void rereadsPendingOnlyWhenConditionalTransitionLosesRace() {
        CommentOutboxEvent event = event();
        when(commentMapper.insertIgnore(any(Comment.class))).thenReturn(1);
        when(pendingMapper.updateStatusIfCurrent(101L, "succeeded", "pending")).thenReturn(0);
        when(pendingMapper.findById(101L)).thenReturn(pending("succeeded"));

        service.finalizeMaterialization(event, pending("pending"));

        verify(pendingMapper).findById(101L);
        verify(eventWriter).createdFrom(event);
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

    private PendingComment pending(String status) {
        return PendingComment.builder().pendingCommentId(101L).postId(9L).creatorId(7L)
                .clientRequestId("client-1").status(status)
                .createTime(LocalDateTime.of(2026, 8, 7, 9, 59)).build();
    }
}
