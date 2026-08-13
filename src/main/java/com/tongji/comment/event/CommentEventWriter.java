package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CommentEventWriter {
    private final CommentOutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CommentEventWriter(CommentOutboxMapper outboxMapper,
                              IdService idService,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public long writeRequested(CommentWriteRequest request) {
        CommentOutboxEvent event = new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                CommentEventType.COMMENT_WRITE_REQUESTED,
                request.commentId(),
                request.postId(),
                request.rootId(),
                request.parentId(),
                request.creatorId(),
                request.clientRequestId(),
                request.body(),
                request.occurredAt()
        );
        insert(event, false, false);
        return event.eventId();
    }

    public long createdFrom(CommentOutboxEvent source) {
        return changed(new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                CommentEventType.COMMENT_CREATED,
                source.commentId(),
                source.postId(),
                source.rootId(),
                source.parentId(),
                source.creatorId(),
                source.clientRequestId(),
                null,
                source.occurredAt()
        ));
    }

    public long deleted(Comment comment) {
        return changed(changeEvent(comment, CommentEventType.COMMENT_DELETED));
    }

    public long moderated(Comment comment) {
        return changed(changeEvent(comment, CommentEventType.COMMENT_MODERATED));
    }

    private long changed(CommentOutboxEvent event) {
        insert(event, true, true);
        return event.eventId();
    }

    private CommentOutboxEvent changeEvent(Comment comment, CommentEventType eventType) {
        return new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                eventType,
                comment.getCommentId(),
                comment.getPostId(),
                comment.getRootId(),
                comment.getParentId(),
                comment.getCreatorId(),
                comment.getClientRequestId(),
                null,
                LocalDateTime.now()
        );
    }

    private void insert(CommentOutboxEvent event, boolean ignoreDuplicate, boolean publishLocal) {
        LocalDateTime now = LocalDateTime.now();
        CommentOutbox row = CommentOutbox.builder()
                .eventId(event.eventId())
                .eventType(event.eventType().name())
                .aggregateId(event.commentId())
                .payload(serialize(event))
                .nextAttemptAt(now)
                .createdAt(now)
                .build();
        if (ignoreDuplicate) {
            outboxMapper.insertIgnore(row);
        } else {
            outboxMapper.insert(row);
        }
        if (publishLocal) {
            eventPublisher.publishEvent(new CommentMutationEvent(
                    event.eventId(),
                    event.eventType(),
                    event.commentId(),
                    event.postId(),
                    value(event.rootId()),
                    value(event.parentId())
            ));
        }
    }

    private String serialize(CommentOutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("comment event serialization failed", exception);
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
