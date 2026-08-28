package com.tongji.comment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 评论异步提交请求。
 *
 * <p>正文上限使最坏 UTF-8 payload 仍远低于 Kafka 默认消息上限；幂等键上限与 MySQL 字段一致。</p>
 *
 * @since 2026-08-28
 */
public record CommentSubmitRequest(
        Long postId,
        Long rootId,
        Long parentId,
        @NotBlank(message = "clientRequestId is required")
        @Size(max = MAX_CLIENT_REQUEST_ID_LENGTH, message = "clientRequestId too long")
        String clientRequestId,
        @NotBlank(message = "comment body is required")
        @Size(max = MAX_BODY_LENGTH, message = "comment body too long")
        String body
) {
    public static final int MAX_CLIENT_REQUEST_ID_LENGTH = 64;
    public static final int MAX_BODY_LENGTH = 4_000;
}
