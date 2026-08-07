package com.tongji.comment.cache;

import com.tongji.comment.event.CommentEventType;

public record CommentMutationEvent(
        CommentEventType eventType,
        long commentId,
        long postId,
        long rootId,
        long parentId) {
}
