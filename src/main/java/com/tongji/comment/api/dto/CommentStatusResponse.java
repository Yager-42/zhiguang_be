package com.tongji.comment.api.dto;

public record CommentStatusResponse(
        Long pendingCommentId,
        String clientRequestId,
        String status
) {}
