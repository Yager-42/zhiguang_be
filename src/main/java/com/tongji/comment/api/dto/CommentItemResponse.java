package com.tongji.comment.api.dto;

import java.time.LocalDateTime;

public record CommentItemResponse(
        String commentId,
        String postId,
        String rootId,
        String parentId,
        String creatorId,
        String creatorNickname,
        String creatorAvatar,
        String body,
        Integer status,
        boolean deleted,
        Integer likeCount,
        Integer replyCount,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        boolean liked
) {}
