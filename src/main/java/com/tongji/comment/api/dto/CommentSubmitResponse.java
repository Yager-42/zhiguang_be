package com.tongji.comment.api.dto;

public record CommentSubmitResponse(
        String clientRequestId,
        Long pendingCommentId,
        String status
) {}
