package com.tongji.comment.api.dto;

public record CommentSubmitRequest(
        Long postId,
        Long rootId,
        Long parentId,
        String clientRequestId,
        String body
) {}
