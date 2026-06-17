package com.tongji.comment.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CommentPageResponse(
        List<CommentItemResponse> items,
        LocalDateTime nextCursorCreateTime,
        Long nextCursorCommentId,
        boolean hasMore
) {}
