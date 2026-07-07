package com.tongji.comment.api.dto;

public record CommentStatusResponse(
        String pendingCommentId,
        String clientRequestId,
        String status
) {}
