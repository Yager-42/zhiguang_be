package com.tongji.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.cache.CommentPageCacheService;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.service.impl.CommentMutationService;
import com.tongji.comment.service.impl.CommentServiceImpl;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.counter.service.CounterService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceImplTest {

    @Test
    void submitWritesPendingAndUnifiedOutboxInTransactionalMethod() throws Exception {
        CommentMapper commentMapper = mock(CommentMapper.class);
        PendingCommentMapper pendingMapper = mock(PendingCommentMapper.class);
        TextStorageService textStorageService = mock(TextStorageService.class);
        IdService idService = mock(IdService.class);
        CommentOutboxMapper outboxMapper = mock(CommentOutboxMapper.class);
        CounterService counterService = mock(CounterService.class);
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(101L);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(201L);
        CommentServiceImpl service = new CommentServiceImpl(commentMapper, pendingMapper, textStorageService,
                idService, outboxMapper, counterService, new ObjectMapper().findAndRegisterModules(),
                mock(CommentPageCacheService.class), Runnable::run, mock(CommentMutationService.class),
                mock(CommentMetrics.class));

        var response = service.submit(7L, 9L,
                new CommentSubmitRequest(9L, null, null, "client-1", "hello"));

        assertThat(response.pendingCommentId()).isEqualTo("101");
        verify(pendingMapper).insert(any(PendingComment.class));
        verify(outboxMapper).insert(org.mockito.ArgumentMatchers.argThat((CommentOutbox outbox) ->
                outbox.getEventId().equals(201L)
                        && outbox.getAggregateId().equals(101L)
                        && "COMMENT_WRITE_REQUESTED".equals(outbox.getEventType())
                        && outbox.getPayload().contains("\"eventId\":201")));
        verify(textStorageService, never()).saveCommentText(any(Long.class), any(String.class));

        Method submit = CommentServiceImpl.class.getMethod(
                "submit", long.class, long.class, CommentSubmitRequest.class);
        assertThat(submit.getAnnotation(Transactional.class)).isNotNull();
    }
}
