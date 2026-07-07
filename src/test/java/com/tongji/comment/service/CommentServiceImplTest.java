package com.tongji.comment.service;

import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.event.CommentWriteProducer;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.counter.service.CounterService;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentServiceImpl;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class CommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private PendingCommentMapper pendingCommentMapper;
    @Mock
    private TextStorageService textStorageService;
    @Mock
    private IdService idService;
    @Mock
    private CommentWriteProducer commentWriteProducer;
    @Mock
    private CommentFeedbackProducer commentFeedbackProducer;
    @Mock
    private CounterService counterService;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        commentService = new CommentServiceImpl(
                commentMapper,
                pendingCommentMapper,
                textStorageService,
                idService,
                commentWriteProducer,
                counterService
        );
    }

    @Test
    void submitHasTransactionalBoundaryForPendingInsertAndPublish() throws Exception {
        Method submit = CommentServiceImpl.class.getMethod("submit", long.class, long.class, CommentSubmitRequest.class);

        assertThat(submit.getAnnotation(Transactional.class))
                .withFailMessage("submit must be transactional so pending insert rolls back if Kafka publish fails")
                .isNotNull();
    }

    @Test
    void submitNewRequestInsertsPendingAndPublishesWriteWithoutFeedback() {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(101L);
        CommentSubmitRequest request = new CommentSubmitRequest(9L, null, null, "client-1", "hello");

        CommentSubmitResponse response = commentService.submit(7L, 9L, request);

        assertThat(response.clientRequestId()).isEqualTo("client-1");
        assertThat(response.pendingCommentId()).isEqualTo("101");
        assertThat(response.status()).isEqualTo("pending");
        ArgumentCaptor<PendingComment> pendingCaptor = ArgumentCaptor.forClass(PendingComment.class);
        verify(pendingCommentMapper).insert(pendingCaptor.capture());
        assertThat(pendingCaptor.getValue()).satisfies(pending -> {
            assertThat(pending.getPendingCommentId()).isEqualTo(101L);
            assertThat(pending.getPostId()).isEqualTo(9L);
            assertThat(pending.getCreatorId()).isEqualTo(7L);
            assertThat(pending.getClientRequestId()).isEqualTo("client-1");
            assertThat(pending.getStatus()).isEqualTo("pending");
        });
        verify(commentWriteProducer).publish(new CommentWriteEvent(101L, 9L, 0L, 0L, 7L, "client-1", "hello"));
        verifyNoInteractions(commentFeedbackProducer);
        verify(commentMapper, never()).insert(any());
        verify(textStorageService, never()).saveCommentText(anyLong(), anyString());
    }

    @Test
    void submitDoesNotTouchFeedbackProducer() {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(101L);
        CommentSubmitRequest request = new CommentSubmitRequest(9L, null, null, "client-1", "hello");

        assertThatCode(() -> commentService.submit(7L, 9L, request))
                .doesNotThrowAnyException();

        verify(pendingCommentMapper).insert(any(PendingComment.class));
        verify(commentWriteProducer).publish(new CommentWriteEvent(101L, 9L, 0L, 0L, 7L, "client-1", "hello"));
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void duplicateSubmitReturnsExistingPendingWithoutNewIdOrPublish() {
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1"))
                .thenReturn(PendingComment.builder()
                        .pendingCommentId(101L)
                        .creatorId(7L)
                        .clientRequestId("client-1")
                        .status("pending")
                        .build());

        CommentSubmitResponse response = commentService.submit(7L, 9L,
                new CommentSubmitRequest(9L, null, null, "client-1", "hello"));

        assertThat(response.pendingCommentId()).isEqualTo("101");
        assertThat(response.status()).isEqualTo("pending");
        verifyNoInteractions(idService, commentWriteProducer, commentFeedbackProducer);
        verify(pendingCommentMapper, never()).insert(any());
    }

    @Test
    void duplicateSubmitRaceReturnsExistingPendingWithoutPublish() {
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-race"))
                .thenReturn(null)
                .thenReturn(PendingComment.builder()
                        .pendingCommentId(101L)
                        .creatorId(7L)
                        .clientRequestId("client-race")
                        .status("pending")
                        .build());
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(102L);
        when(pendingCommentMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate pending"));

        CommentSubmitResponse response = commentService.submit(7L, 9L,
                new CommentSubmitRequest(9L, null, null, "client-race", "hello"));

        assertThat(response.clientRequestId()).isEqualTo("client-race");
        assertThat(response.pendingCommentId()).isEqualTo("101");
        assertThat(response.status()).isEqualTo("pending");
        verify(commentWriteProducer, never()).publish(any());
        verify(commentFeedbackProducer, never()).publish(any());
    }

    @Test
    void submitRejectsRootWithoutParent() {
        assertThatThrownBy(() -> commentService.submit(7L, 9L,
                new CommentSubmitRequest(9L, 20L, null, "client-root-only", "reply")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verifyNoInteractions(pendingCommentMapper, idService, commentWriteProducer, commentFeedbackProducer);
    }

    @Test
    void submitRejectsReplyToReply() {
        when(commentMapper.findById(20L)).thenReturn(Comment.builder()
                .commentId(20L)
                .parentId(10L)
                .status(0)
                .build());

        assertThatThrownBy(() -> commentService.submit(7L, 9L,
                new CommentSubmitRequest(9L, 10L, 20L, "client-2", "reply")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verifyNoInteractions(idService, commentWriteProducer, commentFeedbackProducer);
        verify(pendingCommentMapper, never()).insert(any());
    }

    @Test
    void submitRejectsReplyWithParentFromDifferentPost() {
        when(commentMapper.findById(20L)).thenReturn(Comment.builder()
                .commentId(20L)
                .postId(8L)
                .parentId(0L)
                .status(0)
                .build());

        assertThatThrownBy(() -> commentService.submit(7L, 9L,
                new CommentSubmitRequest(9L, 20L, 20L, "client-cross-post", "reply")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verifyNoInteractions(idService, commentWriteProducer, commentFeedbackProducer);
        verify(pendingCommentMapper, never()).insert(any());
    }

    @Test
    void statusReadsPendingTable() {
        when(pendingCommentMapper.findById(101L)).thenReturn(PendingComment.builder()
                .pendingCommentId(101L)
                .clientRequestId("client-1")
                .status("accepted")
                .build());

        CommentStatusResponse response = commentService.status(101L);

        assertThat(response.pendingCommentId()).isEqualTo("101");
        assertThat(response.clientRequestId()).isEqualTo("client-1");
        assertThat(response.status()).isEqualTo("accepted");
    }

    @Test
    void pageCommentsReadsMetadataAndBatchTextsWithDeletedPlaceholder() {
        LocalDateTime now = LocalDateTime.now();
        when(commentMapper.listTopLevelByPost(9L, null, null, 3)).thenReturn(List.of(
                comment(101L, 9L, 0L, 0L, 0, now),
                comment(100L, 9L, 0L, 0L, 1, now.minusSeconds(1)),
                comment(99L, 9L, 0L, 0L, 0, now.minusSeconds(2))
        ));
        when(textStorageService.getCommentTexts(anyCollection())).thenReturn(Map.of(
                101L, "first",
                99L, "third"
        ));
        when(counterService.isLiked(anyString(), anyString(), anyLong())).thenReturn(false);

        CommentPageResponse response = commentService.pageComments(9L, null, null, 2, 7L);

        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursorCommentId()).isEqualTo("100");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).body()).isEqualTo("first");
        assertThat(response.items().get(1).deleted()).isTrue();
        assertThat(response.items().get(1).body()).isEqualTo("[deleted]");
        verify(textStorageService).getCommentTexts(List.of(101L));
    }

    @Test
    void pageCommentsRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> commentService.pageComments(9L, null, null, 0, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(commentMapper, never()).listTopLevelByPost(anyLong(), any(), any(), anyInt());
    }

    @Test
    void pageRepliesRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> commentService.pageReplies(101L, null, null, -1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(commentMapper, never()).listRepliesByRoot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void pageCommentsRejectsLimitAboveMaxPageSize() {
        assertThatThrownBy(() -> commentService.pageComments(9L, null, null, 101, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(commentMapper, never()).listTopLevelByPost(anyLong(), any(), any(), anyInt());
    }

    @Test
    void pageRepliesRejectsIntegerMaxLimit() {
        assertThatThrownBy(() -> commentService.pageReplies(101L, null, null, Integer.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(commentMapper, never()).listRepliesByRoot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void deleteWhenTextDeleteFailsDoesNotSoftDelete() {
        RuntimeException failure = new RuntimeException("cassandra unavailable");
        when(commentMapper.findById(101L)).thenReturn(comment(101L, 9L, 0L, 0L, 0, LocalDateTime.now()));
        doThrow(failure).when(textStorageService).deleteCommentText(101L);

        assertThatThrownBy(() -> commentService.delete(7L, 101L))
                .isSameAs(failure);

        verify(textStorageService).deleteCommentText(101L);
        verify(commentMapper, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void deleteByOwnerDeletesTextThenSoftDeletes() {
        when(commentMapper.findById(101L)).thenReturn(comment(101L, 9L, 0L, 0L, 0, LocalDateTime.now()));
        when(commentMapper.softDelete(101L, 7L)).thenReturn(1);

        commentService.delete(7L, 101L);

        var ordered = inOrder(commentMapper, textStorageService);
        ordered.verify(commentMapper).findById(101L);
        ordered.verify(textStorageService).deleteCommentText(101L);
        ordered.verify(commentMapper).softDelete(101L, 7L);
    }

    @Test
    void deleteRejectsMissingCommentWithoutDeletingText() {
        when(commentMapper.findById(101L)).thenReturn(null);

        assertThatThrownBy(() -> commentService.delete(7L, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(textStorageService, never()).deleteCommentText(anyLong());
        verify(commentMapper, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void deleteRejectsCommentOwnedByAnotherUserWithoutDeletingText() {
        when(commentMapper.findById(101L)).thenReturn(Comment.builder()
                .commentId(101L)
                .creatorId(8L)
                .status(0)
                .build());

        assertThatThrownBy(() -> commentService.delete(7L, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(textStorageService, never()).deleteCommentText(anyLong());
        verify(commentMapper, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void deleteRejectsAlreadyDeletedCommentWithoutDeletingText() {
        when(commentMapper.findById(101L)).thenReturn(comment(101L, 9L, 0L, 0L, 1, LocalDateTime.now()));

        assertThatThrownBy(() -> commentService.delete(7L, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(textStorageService, never()).deleteCommentText(anyLong());
        verify(commentMapper, never()).softDelete(anyLong(), anyLong());
    }

    private static Comment comment(Long commentId,
                                   Long postId,
                                   Long rootId,
                                   Long parentId,
                                   Integer status,
                                   LocalDateTime createTime) {
        return Comment.builder()
                .commentId(commentId)
                .postId(postId)
                .rootId(rootId)
                .parentId(parentId)
                .creatorId(7L)
                .status(status)
                .likeCount(0)
                .replyCount(0)
                .createTime(createTime)
                .updateTime(createTime)
                .build();
    }
}
