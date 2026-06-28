package com.tongji.moderation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationReport {
    private Long id;
    private Long reporterUserId;
    private String targetType;
    private Long targetId;
    private Long targetOwnerUserId;
    private String reason;
    private String description;
    private String status;
    private String llmProvider;
    private String llmModel;
    private String llmDecision;
    private BigDecimal llmConfidence;
    private String llmSummary;
    private String failureCode;
    private String failureReason;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String contentActionStatus;
    private String contentActionFailure;
    private String notificationFailure;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
