package com.tongji.reconciliation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationTask {
    private Long id;
    private String taskType;
    private String targetType;
    private Long targetId;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextExecuteAt;
    private Long executionDurationMs;
    private String dedupeScope;
    private String taskPayload;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
