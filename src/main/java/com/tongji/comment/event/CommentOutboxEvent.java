package com.tongji.comment.event;

import java.time.LocalDateTime;

/**
 * 评论领域 outbox 的稳定事件信封。
 */
public record CommentOutboxEvent(
        Long eventId,
        CommentEventType eventType,
        Long commentId,
        Long postId,
        Long rootId,
        Long parentId,
        Long creatorId,
        String clientRequestId,
        String body,
        LocalDateTime occurredAt) {
}
