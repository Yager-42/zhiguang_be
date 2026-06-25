package com.tongji.reconciliation.model;

import java.util.List;

public final class ReconciliationTaskType {
    public static final String ES_INDEX = "es_index";
    public static final String FEED_CACHE_INVALIDATE = "feed_cache_invalidate";
    public static final String GORSE_ITEM_UPSERT = "gorse_item_upsert";
    public static final String GORSE_FEEDBACK = "gorse_feedback";
    public static final String CASSANDRA_TEXT = "cassandra_text";
    public static final String COMMENT_COUNT = "comment_count";
    public static final String FOLLOW_GRAPH = "follow_graph";
    public static final String FOLLOW_INBOX = "follow_inbox";
    public static final String PROMOTION_DECISION_PROJECTION = "promotion_decision_projection";
    public static final String PROMOTION_ALLOCATION_REBUILD = "promotion_allocation_rebuild";
    public static final String PROMOTION_WALLET_EFFECT_REPAIR = "promotion_wallet_effect_repair";

    public static final List<String> ALL = List.of(
            ES_INDEX,
            FEED_CACHE_INVALIDATE,
            GORSE_ITEM_UPSERT,
            GORSE_FEEDBACK,
            CASSANDRA_TEXT,
            COMMENT_COUNT,
            FOLLOW_GRAPH,
            FOLLOW_INBOX,
            PROMOTION_DECISION_PROJECTION,
            PROMOTION_ALLOCATION_REBUILD,
            PROMOTION_WALLET_EFFECT_REPAIR
    );

    private ReconciliationTaskType() {
    }
}
