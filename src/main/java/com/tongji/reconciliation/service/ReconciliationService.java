package com.tongji.reconciliation.service;

import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;

import java.util.List;

public interface ReconciliationService {
    ReconciliationTask createTask(String taskType, String targetType, Long targetId);

    ReconciliationTask createTask(String taskType, String targetType, Long targetId, String taskPayload);

    ReconciliationTask createTaskIfAbsent(String taskType, String targetType, Long targetId);

    ReconciliationTask createTaskIfAbsent(String taskType, String targetType, Long targetId, String taskPayload);

    ReconciliationTask createDeadTaskIfAbsent(String taskType, String targetType, Long targetId, String lastError);

    ReconciliationTask createDeadTaskIfAbsent(String taskType, String targetType, Long targetId,
                                              String taskPayload, String lastError);

    ReconciliationTask retryTask(Long taskId);

    List<ReconciliationTask> rerunTarget(String targetType, Long targetId);

    ReconciliationTask findById(Long taskId);

    List<ReconciliationTask> query(ReconciliationTaskQuery query);
}
