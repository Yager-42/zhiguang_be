package com.tongji.comment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class CommentWriteConsumer {
    private static final int COMMENT_COUNT_IDX = 3;

    private final ObjectMapper objectMapper;
    private final CommentMapper commentMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final TextStorageService textStorageService;
    private final CounterEventProducer counterEventProducer;
    private final CommentFeedbackProducer commentFeedbackProducer;

    public CommentWriteConsumer(ObjectMapper objectMapper,
                                CommentMapper commentMapper,
                                PendingCommentMapper pendingCommentMapper,
                                TextStorageService textStorageService,
                                CounterEventProducer counterEventProducer,
                                CommentFeedbackProducer commentFeedbackProducer) {
        this.objectMapper = objectMapper;
        this.commentMapper = commentMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.textStorageService = textStorageService;
        this.counterEventProducer = counterEventProducer;
        this.commentFeedbackProducer = commentFeedbackProducer;
    }

    @RetryableTopic
    @KafkaListener(
            topics = "${comment.kafka.write-topic:comment-write}",
            groupId = "comment-write-consumer",
            containerFactory = "commentWriteKafkaListenerContainerFactory"
    )
    @Transactional
    public void onMessage(String message) {
        handle(read(message));
    }

    void handle(CommentWriteEvent event) {
        PendingComment pending = pendingCommentMapper.findByCreatorAndClientRequestId(
                event.creatorId(), event.clientRequestId());
        if (pending == null) {
            throw new IllegalStateException("missing pending comment");
        }
        if ("succeeded".equals(pending.getStatus())
                || !event.commentId().equals(pending.getPendingCommentId())) {
            return;
        }

        Long commentId = pending.getPendingCommentId();
        textStorageService.saveCommentText(commentId, event.body());
        try {
            commentMapper.insert(comment(event));
        } catch (DuplicateKeyException exception) {
            pendingCommentMapper.updateStatus(commentId, "succeeded");
            return;
        }

        pendingCommentMapper.updateStatus(commentId, "succeeded");
        counterEventProducer.publish(counter(event));
        commentFeedbackProducer.publish(feedback(event));
    }

    @DltHandler
    public void onDlt(String message) {
        onDlt(read(message));
    }

    void onDlt(CommentWriteEvent event) {
        pendingCommentMapper.updateStatusIfCurrent(event.commentId(), "failed", "pending");
    }

    private CommentWriteEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentWriteEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment write event", exception);
        }
    }

    private Comment comment(CommentWriteEvent event) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .commentId(event.commentId())
                .postId(event.postId())
                .rootId(event.rootId())
                .parentId(event.parentId())
                .creatorId(event.creatorId())
                .clientRequestId(event.clientRequestId())
                .status(0)
                .likeCount(0)
                .replyCount(0)
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private CounterEvent counter(CommentWriteEvent event) {
        if (event.parentId() != null && event.parentId() != 0L) {
            return CounterEvent.of("comment", String.valueOf(event.rootId()), "comment",
                    COMMENT_COUNT_IDX, event.creatorId(), 1);
        }
        return CounterEvent.of("knowpost", String.valueOf(event.postId()), "comment",
                COMMENT_COUNT_IDX, event.creatorId(), 1);
    }

    private CommentFeedbackEvent feedback(CommentWriteEvent event) {
        return new CommentFeedbackEvent(event.commentId(), event.postId(), event.rootId(), event.parentId(),
                event.creatorId(), CommentFeedbackEvent.COMMENT);
    }
}
