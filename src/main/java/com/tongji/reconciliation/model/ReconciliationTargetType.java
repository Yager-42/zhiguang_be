package com.tongji.reconciliation.model;

import java.util.List;

public final class ReconciliationTargetType {
    public static final String POST = "post";
    public static final String COMMENT = "comment";
    public static final String USER = "user";

    public static final List<String> ALL = List.of(POST, COMMENT, USER);

    private ReconciliationTargetType() {
    }
}
