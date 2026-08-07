package com.tongji.comment.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CommentMutationService {
    private final CommentMapper commentMapper;
    private final CommentOutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CommentMutationService(CommentMapper commentMapper,
                                  CommentOutboxMapper outboxMapper,
                                  IdService idService,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.commentMapper = commentMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void deleteFinalizer(Comment comment, long creatorId) {
        if (commentMapper.softDelete(comment.getCommentId(), creatorId) == 0) {
            throw new IllegalStateException("comment delete state changed concurrently");
        }
        emit(comment, CommentEventType.COMMENT_DELETED);
    }

    @Transactional
    public void moderate(long commentId) {
        Comment comment = commentMapper.findById(commentId);
        if (comment == null) {
            throw new IllegalStateException("comment moderation target does not exist");
        }
        if (Integer.valueOf(1).equals(comment.getStatus())) {
            return;
        }
        if (commentMapper.softDeleteForModeration(commentId) == 0) {
            throw new IllegalStateException("comment moderation state changed concurrently");
        }
        emit(comment, CommentEventType.COMMENT_MODERATED);
    }

    private void emit(Comment comment, CommentEventType eventType) {
        long eventId = idService.nextId(IdNamespace.OUTBOX_EVENT);
        LocalDateTime now = LocalDateTime.now();
        CommentOutboxEvent event = new CommentOutboxEvent(eventId, eventType, comment.getCommentId(),
                comment.getPostId(), comment.getRootId(), comment.getParentId(), comment.getCreatorId(),
                comment.getClientRequestId(), null, now);
        outboxMapper.insertIgnore(CommentOutbox.builder()
                .eventId(eventId)
                .eventType(eventType.name())
                .aggregateId(comment.getCommentId())
                .payload(serialize(event))
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
        eventPublisher.publishEvent(new CommentMutationEvent(eventId, eventType, comment.getCommentId(),
                comment.getPostId(), value(comment.getRootId()), value(comment.getParentId())));
    }

    private String serialize(CommentOutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("comment mutation event serialization failed", exception);
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
