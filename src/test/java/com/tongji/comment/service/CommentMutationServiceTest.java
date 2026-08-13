package com.tongji.comment.service;

import com.tongji.comment.event.CommentEventWriter;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.service.impl.CommentMutationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentMutationServiceTest {
    private CommentMapper commentMapper;
    private CommentEventWriter eventWriter;
    private CommentMutationService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        eventWriter = mock(CommentEventWriter.class);
        service = new CommentMutationService(commentMapper, eventWriter);
    }

    @Test
    void deleteWritesDeletedEventAfterGuardedStateChange() {
        Comment comment = comment();
        when(commentMapper.softDelete(101L, 7L)).thenReturn(1);

        service.deleteFinalizer(comment, 7L);

        verify(eventWriter).deleted(comment);
    }

    @Test
    void deleteRollbackBoundaryRejectsConcurrentStateChangeBeforeEvent() {
        when(commentMapper.softDelete(101L, 7L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteFinalizer(comment(), 7L))
                .hasMessageContaining("concurrently");

        verify(eventWriter, never()).deleted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void moderationWritesModeratedEvent() {
        Comment comment = comment();
        when(commentMapper.findById(101L)).thenReturn(comment);
        when(commentMapper.softDeleteForModeration(101L)).thenReturn(1);

        service.moderate(101L);

        verify(eventWriter).moderated(comment);
    }

    private Comment comment() {
        return Comment.builder().commentId(101L).postId(9L).rootId(0L).parentId(0L)
                .creatorId(7L).clientRequestId("client-1").status(0).build();
    }
}
