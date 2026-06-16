package com.tongji.knowpost.api.dto;

/**
 * 发布状态查询响应。
 */
public record PublishStatusResponse(
        String publishAttemptId,
        String attemptStatus,
        String postStatus,
        String failedStep,
        boolean retryable
) {}
