package com.tongji.comment.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.tongji.comment.cache.CommentMutationEvent;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.time.Duration;

@Service
public class CommentMaterializationService {
    private static final String PENDING = "pending";
    private static final String SUCCEEDED = "succeeded";

    private final CommentMapper commentMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final CommentOutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CommentMetrics metrics;

    public CommentMaterializationService(CommentMapper commentMapper,
                                         PendingCommentMapper pendingCommentMapper,
                                         CommentOutboxMapper outboxMapper,
                                         IdService idService,
                                         ObjectMapper objectMapper,
                                         ApplicationEventPublisher eventPublisher,
                                         CommentMetrics metrics) {
        this.commentMapper = commentMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    @Transactional
    public void finalizeMaterialization(CommentOutboxEvent event, PendingComment pending) {
        validatePending(event, pending);

        Comment candidate = toComment(event);
        if (commentMapper.insertIgnore(candidate) == 0) {
            validateCanonical(event, commentMapper.findById(event.commentId()));
        }
        if (!SUCCEEDED.equals(pending.getStatus())) {
            int updated = pendingCommentMapper.updateStatusIfCurrent(event.commentId(), SUCCEEDED, PENDING);
            if (updated == 0) {
                PendingComment current = pendingCommentMapper.findById(event.commentId());
                validatePending(event, current);
                if (!SUCCEEDED.equals(current.getStatus())) {
                    throw new IllegalStateException("pending comment cannot transition to succeeded");
                }
            }
        }
        insertCreatedEvent(event);
        metrics.materialized("succeeded");
        if (pending.getCreateTime() != null) {
            metrics.acceptedToSucceeded(Duration.between(pending.getCreateTime(), LocalDateTime.now()));
        }
    }

    private void insertCreatedEvent(CommentOutboxEvent source) {
        long eventId = idService.nextId(IdNamespace.OUTBOX_EVENT);
        CommentOutboxEvent created = new CommentOutboxEvent(eventId, CommentEventType.COMMENT_CREATED,
                source.commentId(), source.postId(), source.rootId(), source.parentId(), source.creatorId(),
                source.clientRequestId(), null, source.occurredAt());
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.insertIgnore(CommentOutbox.builder()
                .eventId(eventId)
                .eventType(CommentEventType.COMMENT_CREATED.name())
                .aggregateId(source.commentId())
                .payload(serialize(created))
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
        eventPublisher.publishEvent(new CommentMutationEvent(eventId, CommentEventType.COMMENT_CREATED,
                source.commentId(), source.postId(), value(source.rootId()), value(source.parentId())));
    }

    private Comment toComment(CommentOutboxEvent event) {
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
                .createTime(event.occurredAt())
                .updateTime(event.occurredAt())
                .build();
    }

    private void validatePending(CommentOutboxEvent event, PendingComment pending) {
        if (pending == null
                || !Objects.equals(pending.getPendingCommentId(), event.commentId())
                || !Objects.equals(pending.getCreatorId(), event.creatorId())
                || !Objects.equals(pending.getClientRequestId(), event.clientRequestId())
                || "failed".equals(pending.getStatus())) {
            throw new IllegalStateException("comment event does not match canonical pending row");
        }
    }

    private void validateCanonical(CommentOutboxEvent event, Comment comment) {
        if (comment == null
                || !Objects.equals(comment.getCommentId(), event.commentId())
                || !Objects.equals(comment.getPostId(), event.postId())
                || !Objects.equals(comment.getRootId(), event.rootId())
                || !Objects.equals(comment.getParentId(), event.parentId())
                || !Objects.equals(comment.getCreatorId(), event.creatorId())
                || !Objects.equals(comment.getClientRequestId(), event.clientRequestId())) {
            throw new IllegalStateException("comment metadata conflicts with canonical row");
        }
    }

    private String serialize(CommentOutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("comment created event serialization failed", exception);
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
