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
    private Integer runVersion;
    private String failedStep;
    private String errorMessage;
    private String contentObjectKeySnapshot;
    private String contentEtagSnapshot;
    private String contentSha256Snapshot;
    private Integer retryCount;
    private Instant createdAt;
    private Instant updatedAt;
}
