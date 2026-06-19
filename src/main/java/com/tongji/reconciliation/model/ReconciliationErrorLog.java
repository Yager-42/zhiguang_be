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
public class ReconciliationErrorLog {
    private Long id;
    private Long taskId;
    private Long executionDurationMs;
    private String errorMessage;
    private String stackTrace;
    private LocalDateTime createdAt;
}
