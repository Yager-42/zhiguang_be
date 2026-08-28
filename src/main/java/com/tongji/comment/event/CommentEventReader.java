package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import org.springframework.stereotype.Component;

/**
 * 解析评论业务 payload，并映射本地缓存变更信号。
 *
 * @since 2026-08-28
 */
@Component
public class CommentEventReader {
    private final ObjectMapper objectMapper;

    public CommentEventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析单个评论事件 payload。
     *
     * @param message 评论事件 JSON
     * @return 完整评论事件
     * @throws IllegalArgumentException 当 payload 不是合法评论事件时
     */
    public CommentOutboxEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentOutboxEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment event", exception);
        }
    }

    /**
     * 解析 payload 并在事件属于评论变更时映射缓存信号。
     *
     * @param message 评论事件 JSON
     * @return 评论变更信号；写请求返回 {@code null}
     */
    public CommentMutationEvent mutation(String message) {
        return mutation(read(message));
    }

    /**
     * 将已严格校验的评论事件映射为缓存变更信号。
     *
     * @param event 评论事件
     * @return 评论变更信号；写请求返回 {@code null}
     */
    public CommentMutationEvent mutation(CommentOutboxEvent event) {
        if (!isMutation(event.eventType())) {
            return null;
        }
        return new CommentMutationEvent(
                event.eventId(),
                event.eventType(),
                event.commentId(),
                event.postId(),
                value(event.rootId()),
                value(event.parentId())
        );
    }

    private boolean isMutation(CommentEventType eventType) {
        return eventType == CommentEventType.COMMENT_CREATED
                || eventType == CommentEventType.COMMENT_DELETED
                || eventType == CommentEventType.COMMENT_MODERATED;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
