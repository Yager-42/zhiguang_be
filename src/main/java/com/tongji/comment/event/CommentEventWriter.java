package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import com.tongji.comment.model.Comment;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.outbox.OutboxMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 在评论状态事务内写入具有稳定业务键的共享 Outbox 事件。
 *
 * <p>调用方负责事务边界；唯一 {@code event_key} 保证同一评论事实只产生一个总线事件。</p>
 *
 * @since 2026-08-28
 */
@Service
public class CommentEventWriter {
    private static final String AGGREGATE_TYPE = "comment";

    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CommentEventWriter(OutboxMapper outboxMapper,
                              IdService idService,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 写入评论正文异步物化请求。
     *
     * @param request 已通过提交校验的评论请求快照
     * @return 共享 Outbox 事件 ID
     */
    public long writeRequested(CommentWriteRequest request) {
        CommentOutboxEvent event = new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                CommentEventType.COMMENT_WRITE_REQUESTED,
                CommentOutboxEvent.CURRENT_SCHEMA_VERSION,
                request.commentId(),
                request.postId(),
                request.rootId(),
                request.parentId(),
                request.creatorId(),
                request.clientRequestId(),
                request.body(),
                request.occurredAt()
        );
        persist(event, false);
        return event.eventId();
    }

    /**
     * 写入评论物化完成事实，并发布事务后缓存失效信号。
     *
     * @param source 已完成正文写入的原始评论请求事件
     * @return 共享 Outbox 事件 ID
     */
    public long createdFrom(CommentOutboxEvent source) {
        return changed(new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                CommentEventType.COMMENT_CREATED,
                CommentOutboxEvent.CURRENT_SCHEMA_VERSION,
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

    /**
     * 写入评论删除事实，并发布事务后缓存失效信号。
     *
     * @param comment 已通过属主删除状态转换的评论
     * @return 共享 Outbox 事件 ID
     */
    public long deleted(Comment comment) {
        return changed(changeEvent(comment, CommentEventType.COMMENT_DELETED));
    }

    /**
     * 写入评论审核删除事实，并发布事务后缓存失效信号。
     *
     * @param comment 已通过审核删除状态转换的评论
     * @return 共享 Outbox 事件 ID
     */
    public long moderated(Comment comment) {
        return changed(changeEvent(comment, CommentEventType.COMMENT_MODERATED));
    }

    private long changed(CommentOutboxEvent event) {
        persist(event, true);
        return event.eventId();
    }

    private CommentOutboxEvent changeEvent(Comment comment, CommentEventType eventType) {
        return new CommentOutboxEvent(
                idService.nextId(IdNamespace.OUTBOX_EVENT),
                eventType,
                CommentOutboxEvent.CURRENT_SCHEMA_VERSION,
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

    private void persist(CommentOutboxEvent event, boolean publishLocal) {
        outboxMapper.insertUnique(
                event.eventId(),
                eventKey(event),
                AGGREGATE_TYPE,
                event.commentId(),
                event.eventType().name(),
                serialize(event)
        );
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

    private String eventKey(CommentOutboxEvent event) {
        String prefix = switch (event.eventType()) {
            case COMMENT_WRITE_REQUESTED -> "comment-write-requested:";
            case COMMENT_CREATED -> "comment-created:";
            case COMMENT_DELETED -> "comment-deleted:";
            case COMMENT_MODERATED -> "comment-moderated:";
        };
        return prefix + event.commentId();
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
