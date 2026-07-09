package com.tongji.comment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.storage.text.TextStorageService;
import com.tongji.storage.text.TextWriteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommentWriteConsumerTest {
    @Mock
    private CommentMapper commentMapper;
    @Mock
    private PendingCommentMapper pendingCommentMapper;
    @Mock
    private TextStorageService textStorageService;
    @Mock
    private CounterEventProducer counterEventProducer;
    @Mock
    private CommentFeedbackProducer commentFeedbackProducer;
    @Mock
    private com.tongji.wallet.service.ContentRewardService contentRewardService;

    private CommentWriteConsumer consumer;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        consumer = new CommentWriteConsumer(
                new ObjectMapper(),
                commentMapper,
                pendingCommentMapper,
                textStorageService,
                counterEventProducer,
                commentFeedbackProducer,
                contentRewardService
        );
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1"))
                .thenReturn(pending(101L, "client-1", "pending"));
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-2"))
                .thenReturn(pending(102L, "client-2", "pending"));
    }

    @Test
    void duplicateSucceededPendingSkipsTextAndMetadata() {
        CommentWriteEvent event = topLevelEvent();
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1"))
                .thenReturn(pending(101L, "client-1", "succeeded"));

        consumer.onMessage(json(event));

        verify(textStorageService, never()).saveCommentText(anyLong(), anyString());
        verify(commentMapper, never()).insert(any());
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void duplicatePendingWithDifferentCommentIdSkipsBeforeText() {
        CommentWriteEvent event = new CommentWriteEvent(202L, 9L, 0L, 0L, 7L, "client-1", "hello");
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1"))
                .thenReturn(pending(101L, "client-1", "pending"));

        consumer.handle(event);

        verify(textStorageService, never()).saveCommentText(anyLong(), anyString());
        verify(commentMapper, never()).insert(any());
        verify(pendingCommentMapper, never()).updateStatus(anyLong(), anyString());
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void missingPendingPropagatesBeforeText() {
        CommentWriteEvent event = topLevelEvent();
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1")).thenReturn(null);

        assertThatThrownBy(() -> consumer.handle(event))
                .isInstanceOf(IllegalStateException.class);

        verify(textStorageService, never()).saveCommentText(anyLong(), anyString());
        verify(commentMapper, never()).insert(any());
        verify(pendingCommentMapper, never()).updateStatus(anyLong(), anyString());
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void retryableTextWriteFailurePropagatesAndDoesNotMarkSucceeded() {
        CommentWriteEvent event = topLevelEvent();
        doThrow(new TextWriteException("cassandra down"))
                .when(textStorageService).saveCommentText(101L, "hello");

        assertThatThrownBy(() -> consumer.handle(event))
                .isInstanceOf(TextWriteException.class);

        verify(commentMapper, never()).insert(any());
        verify(pendingCommentMapper, never()).updateStatus(101L, "succeeded");
        verify(pendingCommentMapper, never()).updateStatusByCreatorAndClientRequestId(anyLong(), anyString(), anyString());
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void dltMarksPendingFailedOnlyIfStillPending() {
        consumer.onDlt(topLevelEvent());

        verify(pendingCommentMapper).updateStatusIfCurrent(101L, "failed", "pending");
    }

    @Test
    void dltHandlerAcceptsRawKafkaJsonPayload() {
        Method dltHandler = Arrays.stream(CommentWriteConsumer.class.getMethods())
                .filter(method -> method.getAnnotation(DltHandler.class) != null)
                .findFirst()
                .orElseThrow();

        assertThat(dltHandler.getParameterTypes()[0]).isEqualTo(String.class);

        consumer.onDlt(json(topLevelEvent()));

        verify(pendingCommentMapper).updateStatusIfCurrent(101L, "failed", "pending");
    }

    @Test
    void topLevelSuccessWritesTextThenMetadataMarksSucceededAndPublishesPostCommentCountAndFeedback() {
        CommentWriteEvent event = topLevelEvent();

        consumer.handle(event);

        InOrder order = inOrder(textStorageService, commentMapper, pendingCommentMapper,
                counterEventProducer, commentFeedbackProducer, contentRewardService);
        ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
        order.verify(textStorageService).saveCommentText(101L, "hello");
        order.verify(commentMapper).insert(commentCaptor.capture());
        order.verify(pendingCommentMapper).updateStatus(101L, "succeeded");
        ArgumentCaptor<CounterEvent> counterCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        order.verify(counterEventProducer).publish(counterCaptor.capture());
        ArgumentCaptor<CommentFeedbackEvent> feedbackCaptor = ArgumentCaptor.forClass(CommentFeedbackEvent.class);
        order.verify(commentFeedbackProducer).publish(feedbackCaptor.capture());
        // 评论写入成功后发积分奖励（挂载点：updateStatus succeeded 后）
        order.verify(contentRewardService).rewardCommentCreation(7L, 101L);

        Comment comment = commentCaptor.getValue();
        assertThat(comment.getCommentId()).isEqualTo(101L);
        assertThat(comment.getPostId()).isEqualTo(9L);
        assertThat(comment.getRootId()).isZero();
        assertThat(comment.getParentId()).isZero();
        assertThat(comment.getCreatorId()).isEqualTo(7L);
        assertThat(comment.getClientRequestId()).isEqualTo("client-1");
        assertThat(comment.getStatus()).isZero();
        assertThat(comment.getLikeCount()).isZero();
        assertThat(comment.getReplyCount()).isZero();
        assertThat(comment.getCreateTime()).isNotNull();
        assertThat(comment.getUpdateTime()).isNotNull();
        assertThat(comment.getCreateTime()).isBeforeOrEqualTo(LocalDateTime.now());

        CounterEvent counter = counterCaptor.getValue();
        assertThat(counter.getEntityType()).isEqualTo("knowpost");
        assertThat(counter.getEntityId()).isEqualTo("9");
        assertThat(counter.getMetric()).isEqualTo("comment");
        assertThat(counter.getIdx()).isEqualTo(3);
        assertThat(counter.getUserId()).isEqualTo(7L);
        assertThat(counter.getDelta()).isEqualTo(1);

        assertThat(feedbackCaptor.getValue()).isEqualTo(
                new CommentFeedbackEvent(101L, 9L, 0L, 0L, 7L, CommentFeedbackEvent.COMMENT));
    }

    @Test
    void replySuccessPublishesRootReplyCount() {
        CommentWriteEvent event = new CommentWriteEvent(102L, 9L, 101L, 101L, 7L, "client-2", "reply");

        consumer.handle(event);

        ArgumentCaptor<CounterEvent> counterCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventProducer).publish(counterCaptor.capture());
        CounterEvent counter = counterCaptor.getValue();
        assertThat(counter.getEntityType()).isEqualTo("comment");
        assertThat(counter.getEntityId()).isEqualTo("101");
        assertThat(counter.getMetric()).isEqualTo("comment");
        assertThat(counter.getIdx()).isEqualTo(3);
        assertThat(counter.getUserId()).isEqualTo(7L);
        assertThat(counter.getDelta()).isEqualTo(1);
    }

    @Test
    void duplicateMetadataInsertIsIdempotentRecoveryWithoutRetryOrSideEffects() {
        CommentWriteEvent event = topLevelEvent();
        when(commentMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate comment"));

        consumer.handle(event);

        InOrder order = inOrder(textStorageService, commentMapper, pendingCommentMapper);
        order.verify(textStorageService).saveCommentText(101L, "hello");
        order.verify(commentMapper).insert(any());
        order.verify(pendingCommentMapper).updateStatus(101L, "succeeded");
        verify(textStorageService, never()).saveCommentText(202L, "hello");
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
        // DuplicateKey 幂等恢复路径不调 reward（评论之前已写入，businessRef 幂等由 grant 兜底，此处短路更省一次调用）
        verifyNoInteractions(contentRewardService);
    }

    @Test
    void duplicateMetadataForCanonicalPendingCompletesStatusWithoutCounterOrFeedback() {
        CommentWriteEvent event = topLevelEvent();
        when(pendingCommentMapper.findByCreatorAndClientRequestId(7L, "client-1"))
                .thenReturn(pending(101L, "client-1", "pending"));
        when(commentMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate comment"));

        consumer.handle(event);

        InOrder order = inOrder(textStorageService, commentMapper, pendingCommentMapper);
        order.verify(textStorageService).saveCommentText(101L, "hello");
        order.verify(commentMapper).insert(any());
        order.verify(pendingCommentMapper).updateStatus(101L, "succeeded");
        verify(textStorageService, never()).saveCommentText(202L, "hello");
        verifyNoInteractions(counterEventProducer);
        verifyNoInteractions(commentFeedbackProducer);
    }

    @Test
    void listenerMethodIsTransactional() throws Exception {
        Method onMessage = CommentWriteConsumer.class.getMethod("onMessage", String.class);

        assertThat(onMessage.getAnnotation(Transactional.class)).isNotNull();
    }

    private String json(CommentWriteEvent event) {
        try {
            return new ObjectMapper().writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CommentWriteEvent topLevelEvent() {
        return new CommentWriteEvent(101L, 9L, 0L, 0L, 7L, "client-1", "hello");
    }

    private PendingComment pending(Long pendingCommentId, String clientRequestId, String status) {
        return PendingComment.builder()
                .pendingCommentId(pendingCommentId)
                .creatorId(7L)
                .clientRequestId(clientRequestId)
                .status(status)
                .build();
    }
}
