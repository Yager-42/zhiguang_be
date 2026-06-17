package com.tongji.comment.api.dto;

import java.time.LocalDateTime;

public record CommentItemResponse(
        Long commentId,
        Long postId,
        Long rootId,
        Long parentId,
        Long creatorId,
        String body,
        Integer status,
        boolean deleted,
        Integer likeCount,
        Integer replyCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
