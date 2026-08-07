package com.tongji.comment.event;

import java.time.LocalDateTime;

public record CommentFeedbackEvent(
        String eventId,
        LocalDateTime occurredAt,
        Long commentId,
        Long postId,
        Long rootId,
        Long parentId,
        Long creatorId,
        String action
) {
    public static final String COMMENT = "comment";
    public static final String DELETE = "delete";
    public static final String LIKE = "like";
    public static final String UNLIKE = "unlike";

    public CommentFeedbackEvent(Long commentId,
                                Long postId,
                                Long rootId,
                                Long parentId,
                                Long creatorId,
                                String action) {
        this(null, null, commentId, postId, rootId, parentId, creatorId, action);
    }
}
