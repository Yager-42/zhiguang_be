package com.tongji.comment.event;

public record CommentFeedbackEvent(
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
}
