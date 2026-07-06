package com.tongji.moderation.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationLlmResult;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.model.ModerationStatus;
import com.tongji.moderation.service.ModerationContentActionService;
import com.tongji.moderation.service.ModerationLlmClient;
import com.tongji.moderation.service.ModerationNotificationService;
import com.tongji.moderation.service.ModerationReviewExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class ModerationReviewExecutorImpl implements ModerationReviewExecutor {
    private static final String SINGLE_FLIGHT_STAGE = "moderation-llm";
    private static final TypeReference<ModerationLlmResult> LLM_RESULT_TYPE = new TypeReference<>() {
    };

    private final ModerationReportMapper reportMapper;
    private final ModerationLlmClient llmClient;
    private final ModerationContentActionService contentActionService;
    private final ModerationNotificationService notificationService;
    private final ModerationProperties properties;
    private final DistributedSingleFlightService singleFlightService;

    public ModerationReviewExecutorImpl(ModerationReportMapper reportMapper,
                                        ModerationLlmClient llmClient,
                                        ModerationContentActionService contentActionService,
                                        ModerationNotificationService notificationService,
                                        ModerationProperties properties,
                                        DistributedSingleFlightService singleFlightService) {
        this.reportMapper = reportMapper;
        this.llmClient = llmClient;
        this.contentActionService = contentActionService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.singleFlightService = singleFlightService;
    }

    @Override
    public void review(long reportId) {
        doReview(reportId);
    }

    private void doReview(long reportId) {
        ModerationReport report = reportMapper.findById(reportId);
        if (report == null || !ModerationStatus.PENDING.equals(report.getStatus())) {
            return;
        }

        int currentRetryCount = report.getRetryCount() == null ? 0 : report.getRetryCount();
        ModerationLlmResult result = singleFlightService.execute(
                SINGLE_FLIGHT_STAGE,
                "report:" + reportId + ":retry:" + currentRetryCount,
                LLM_RESULT_TYPE,
                () -> llmClient.review(report)
        );
        if (result.retryableFailure()) {
            handleRetryableFailure(report, result);
            return;
        }
        if (result.failureCode() != null) {
            markIgnored(report, result.failureCode(), result.failureReason(), result);
            return;
        }
        if (!isValidDecision(result.decision())) {
            markIgnored(report, "INVALID_RESPONSE", "LLM decision is not supported", result);
            return;
        }
        if (result.confidence() == null || result.confidence().compareTo(properties.getLlm().getMinConfidence()) < 0) {
            markIgnored(report, "LOW_CONFIDENCE", "LLM confidence is below threshold", result);
            return;
        }
        if (result.summary() == null || result.summary().isBlank()) {
            markIgnored(report, "INVALID_RESPONSE", "LLM summary is empty", result);
            return;
        }

        int updated = reportMapper.markReviewed(
                report.getId(),
                result.decision(),
                result.provider(),
                result.model(),
                result.decision(),
                result.confidence(),
                truncate(result.summary(), 512)
        );
        if (updated == 0) {
            return;
        }

        boolean actionApplied = false;
        if (ModerationStatus.APPROVED.equals(result.decision())) {
            try {
                contentActionService.applyApprovedAction(report);
                actionApplied = true;
                reportMapper.updateContentAction(report.getId(), "success", null, LocalDateTime.now());
            } catch (RuntimeException exception) {
                log.warn("moderation content action failed, reportId={}: {}", report.getId(), exception.getMessage());
                reportMapper.updateContentAction(report.getId(), "failed", truncate(exception.getMessage(), 512), LocalDateTime.now());
            }
        }
        notifyReportProcessed(report, actionApplied);
    }

    private void handleRetryableFailure(ModerationReport report, ModerationLlmResult result) {
        int currentRetryCount = report.getRetryCount() == null ? 0 : report.getRetryCount();
        if (currentRetryCount >= properties.getLlm().getMaxRetries()) {
            markIgnored(report, result.failureCode(), result.failureReason(), result);
            return;
        }
        int nextRetryCount = currentRetryCount + 1;
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(Math.max(1, nextRetryCount));
        reportMapper.scheduleRetry(
                report.getId(),
                currentRetryCount,
                nextRetryCount,
                nextRetryAt,
                result.failureCode(),
                truncate(result.failureReason(), 512),
                LocalDateTime.now()
        );
    }

    private void markIgnored(ModerationReport report,
                             String failureCode,
                             String failureReason,
                             ModerationLlmResult result) {
        int updated = reportMapper.markIgnored(
                report.getId(),
                result.provider(),
                result.model(),
                result.decision(),
                result.confidence(),
                truncate(result.summary(), 512),
                failureCode,
                truncate(failureReason, 512),
                LocalDateTime.now()
        );
        if (updated == 0) {
            return;
        }
        notifyReportProcessed(report, false);
    }

    private void notifyReportProcessed(ModerationReport report, boolean actionApplied) {
        try {
            notificationService.notifyReportProcessed(report, actionApplied);
        } catch (RuntimeException exception) {
            log.warn("moderation notification failed, reportId={}: {}", report.getId(), exception.getMessage());
            reportMapper.updateNotificationFailure(
                    report.getId(),
                    truncate("notification failed: " + exception.getMessage(), 512),
                    LocalDateTime.now()
            );
        }
    }

    private boolean isValidDecision(String decision) {
        return ModerationStatus.APPROVED.equals(decision) || ModerationStatus.REJECTED.equals(decision);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maxLength));
        return value.substring(0, end);
    }
}
