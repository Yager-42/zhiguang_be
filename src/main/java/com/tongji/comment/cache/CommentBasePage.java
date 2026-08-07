package com.tongji.comment.cache;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可写入本地缓存、Redis 和 singleflight 结果的共享评论页。
 */
public record CommentBasePage(
        List<CommentBaseItem> items,
        LocalDateTime nextCursorCreateTime,
        String nextCursorCommentId,
        boolean hasMore) {

    public CommentBasePage {
        items = List.copyOf(items);
    }
}
