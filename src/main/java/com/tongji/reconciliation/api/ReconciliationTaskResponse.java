package com.tongji.reconciliation.api;

import com.tongji.reconciliation.model.ReconciliationTask;

import java.time.LocalDateTime;

public record ReconciliationTaskResponse(
        Long id,
        String taskType,
        String targetType,
        Long targetId,
        String status,
        Integer retryCount,
        LocalDateTime nextExecuteAt,
        Long executionDurationMs,
        String taskPayload,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ReconciliationTaskResponse from(ReconciliationTask task) {
        return new ReconciliationTaskResponse(
                task.getId(),
                task.getTaskType(),
                task.getTargetType(),
                task.getTargetId(),
                task.getStatus(),
                task.getRetryCount(),
                task.getNextExecuteAt(),
                task.getExecutionDurationMs(),
                task.getTaskPayload(),
                task.getLastError(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
