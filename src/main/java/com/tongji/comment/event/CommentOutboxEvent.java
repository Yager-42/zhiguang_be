package com.tongji.comment.event;

import java.time.LocalDateTime;

/**
 * 评论领域共享 Outbox 的稳定事件信封。
 *
 * @param eventId 全局事件 ID
 * @param eventType 评论事件类型
 * @param schemaVersion 载荷协议版本
 * @param commentId 评论 ID
 * @param postId 帖子 ID
 * @param rootId 根评论 ID，顶层评论为 0
 * @param parentId 父评论 ID，顶层评论为 0
 * @param creatorId 评论创建者 ID
 * @param clientRequestId 客户端幂等请求 ID
 * @param body 评论正文；非写请求事件为空
 * @param occurredAt 事件发生时间
 */
public record CommentOutboxEvent(
        Long eventId,
        CommentEventType eventType,
        int schemaVersion,
        Long commentId,
        Long postId,
        Long rootId,
        Long parentId,
        Long creatorId,
        String clientRequestId,
        String body,
        LocalDateTime occurredAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
