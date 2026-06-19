package com.tongji.reconciliation.model;

import java.util.List;

public final class ReconciliationTaskStatus {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String DEAD = "dead";

    public static final List<String> ALL = List.of(PENDING, RUNNING, SUCCEEDED, DEAD);

    public static boolean isActive(String status) {
        return PENDING.equals(status) || RUNNING.equals(status);
    }

    private ReconciliationTaskStatus() {
    }
}
