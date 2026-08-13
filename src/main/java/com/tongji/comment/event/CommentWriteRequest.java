package com.tongji.comment.event;

import java.time.LocalDateTime;

public record CommentWriteRequest(
        long commentId,
        long postId,
        long rootId,
        long parentId,
        long creatorId,
        String clientRequestId,
        String body,
        LocalDateTime occurredAt) {
}
