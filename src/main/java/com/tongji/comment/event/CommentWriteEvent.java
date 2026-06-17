package com.tongji.comment.event;

public record CommentWriteEvent(
        Long commentId,
        Long postId,
        Long rootId,
        Long parentId,
        Long creatorId,
        String clientRequestId,
        String body
) {}
