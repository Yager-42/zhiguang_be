package com.tongji.knowpost.publish;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishAttempt {
    private Long attemptId;
    private Long postId;
    private Long creatorId;
    private String idempotentKey;
    private String status;
    private String failedStep;
    private String errorMessage;
    private String fallbackTaskType;
    private String fallbackTargetType;
    private Long fallbackTargetId;
    private String fallbackFailureReason;
    private Instant fallbackNextRetryAt;
    private Integer retryCount;
    private Instant createdAt;
    private Instant updatedAt;
}
