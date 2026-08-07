package com.tongji.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.service.impl.CommentMutationService;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentMutationServiceTest {
    private CommentMapper commentMapper;
    private CommentOutboxMapper outboxMapper;
    private IdService idService;
    private ApplicationEventPublisher publisher;
    private CommentMutationService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        outboxMapper = mock(CommentOutboxMapper.class);
        idService = mock(IdService.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new CommentMutationService(commentMapper, outboxMapper, idService,
                new ObjectMapper().findAndRegisterModules(), publisher);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(501L);
    }

    @Test
    void deleteWritesDeletedOutboxAfterGuardedStateChange() {
        Comment comment = comment();
        when(commentMapper.softDelete(101L, 7L)).thenReturn(1);

        service.deleteFinalizer(comment, 7L);

        verify(outboxMapper).insertIgnore(org.mockito.ArgumentMatchers.argThat((CommentOutbox row) ->
                "COMMENT_DELETED".equals(row.getEventType()) && row.getAggregateId().equals(101L)));
        verify(publisher).publishEvent(any(CommentMutationEvent.class));
    }

    @Test
    void deleteRollbackBoundaryRejectsConcurrentStateChangeBeforeOutbox() {
        when(commentMapper.softDelete(101L, 7L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteFinalizer(comment(), 7L))
                .hasMessageContaining("concurrently");

        verify(outboxMapper, never()).insertIgnore(any(CommentOutbox.class));
    }

    @Test
    void moderationWritesModeratedOutbox() {
        when(commentMapper.findById(101L)).thenReturn(comment());
        when(commentMapper.softDeleteForModeration(101L)).thenReturn(1);

        service.moderate(101L);

        verify(outboxMapper).insertIgnore(org.mockito.ArgumentMatchers.argThat((CommentOutbox row) ->
                "COMMENT_MODERATED".equals(row.getEventType())));
    }

    private Comment comment() {
        return Comment.builder().commentId(101L).postId(9L).rootId(0L).parentId(0L)
                .creatorId(7L).clientRequestId("client-1").status(0).build();
    }
}
