package com.tongji.comment.api.dto;

public record CommentSubmitResponse(
        String clientRequestId,
        String pendingCommentId,
        String status
) {}
