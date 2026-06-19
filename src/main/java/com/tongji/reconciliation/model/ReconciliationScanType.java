package com.tongji.reconciliation.model;

import java.util.List;

public final class ReconciliationScanType {
    public static final String POST_ES = "post_es";
    public static final String POST_RAG = "post_rag";
    public static final String POST_GORSE = "post_gorse";
    public static final String POST_CASSANDRA = "post_cassandra";
    public static final String COMMENT_CASSANDRA = "comment_cassandra";
    public static final String POST_COMMENT_COUNT = "post_comment_count";
    public static final String COMMENT_REPLY_COUNT = "comment_reply_count";
    public static final String USER_FOLLOW_GRAPH = "user_follow_graph";
    public static final String RUNNING_TIMEOUT = "running_timeout";

    public static final List<String> ALL = List.of(
            POST_ES,
            POST_RAG,
            POST_GORSE,
            POST_CASSANDRA,
            COMMENT_CASSANDRA,
            POST_COMMENT_COUNT,
            COMMENT_REPLY_COUNT,
            USER_FOLLOW_GRAPH,
            RUNNING_TIMEOUT
    );

    private ReconciliationScanType() {
    }
}
