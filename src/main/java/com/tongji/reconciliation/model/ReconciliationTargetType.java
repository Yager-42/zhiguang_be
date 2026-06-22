package com.tongji.reconciliation.model;

import java.util.List;

public final class ReconciliationTargetType {
    public static final String POST = "post";
    public static final String COMMENT = "comment";
    public static final String USER = "user";
    public static final String PROMOTION_DECISION = "promotion_decision";
    public static final String PROMOTION_AUCTION_WINDOW = "promotion_auction_window";

    public static final List<String> ALL = List.of(POST, COMMENT, USER, PROMOTION_DECISION, PROMOTION_AUCTION_WINDOW);

    private ReconciliationTargetType() {
    }
}
