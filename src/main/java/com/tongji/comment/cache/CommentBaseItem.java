package com.tongji.comment.cache;

import java.time.LocalDateTime;

/**
 * 可跨用户共享的评论基础数据，不包含当前用户的点赞状态。
 */
public record CommentBaseItem(
        String commentId,
        String postId,
        String rootId,
        String parentId,
        String creatorId,
        String body,
        Integer status,
        boolean deleted,
        int likeCount,
        int replyCount,
        LocalDateTime createTime,
        LocalDateTime updateTime) {
}
