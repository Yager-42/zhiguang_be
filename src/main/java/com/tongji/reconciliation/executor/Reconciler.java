package com.tongji.reconciliation.executor;

import com.tongji.reconciliation.model.ReconciliationTask;

public interface Reconciler {
    String taskType();

    void reconcile(ReconciliationTask task);
}
