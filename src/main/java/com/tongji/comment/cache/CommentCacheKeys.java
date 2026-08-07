package com.tongji.comment.cache;

/**
 * 评论缓存键的唯一构造入口。
 */
public final class CommentCacheKeys {
    private static final String POST = "post";
    private static final String ROOT = "root";

    private CommentCacheKeys() {
    }

    public static String postHead(long postId, int limit) {
        return head(POST, postId, limit);
    }

    public static String rootHead(long rootId, int limit) {
        return head(ROOT, rootId, limit);
    }

    public static String indexIds(String baseKey) {
        return baseKey + ":ids";
    }

    public static String indexCursor(String baseKey) {
        return baseKey + ":cursor";
    }

    public static String indexHasMore(String baseKey) {
        return baseKey + ":hasMore";
    }

    public static String indexEmpty(String baseKey) {
        return baseKey + ":empty";
    }

    public static String item(long commentId) {
        return "comment:item:" + commentId;
    }

    public static String item(String commentId) {
        return "comment:item:" + commentId;
    }

    public static String postHeadIndex(long postId) {
        return "comment:scope:post:" + postId + ":heads";
    }

    public static String rootHeadIndex(long rootId) {
        return "comment:scope:root:" + rootId + ":heads";
    }

    private static String head(String scope, long scopeId, int limit) {
        return "comment:idx:" + scope + ":" + scopeId + ":head:" + limit;
    }
}
