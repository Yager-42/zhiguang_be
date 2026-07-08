package com.tongji.moderation.service;

import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationLlmResult;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.impl.ModerationReviewExecutorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationReviewExecutorTest {

    private ModerationReportMapper reportMapper;
    private ModerationLlmClient llmClient;
    private ModerationContentActionService contentActionService;
    private ModerationNotificationService notificationService;
    private DistributedSingleFlightService singleFlightService;
    private ModerationReviewExecutor executor;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ModerationReportMapper.class);
        llmClient = mock(ModerationLlmClient.class);
        contentActionService = mock(ModerationContentActionService.class);
        notificationService = mock(ModerationNotificationService.class);
        singleFlightService = mock(DistributedSingleFlightService.class);
        lenient().when(singleFlightService.execute(any(String.class), any(String.class), any(), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.getArgument(3, Supplier.class).get());
        ModerationProperties properties = new ModerationProperties();
        properties.getLlm().setMinConfidence(new BigDecimal("0.8000"));
        properties.getLlm().setMaxRetries(3);
        executor = new ModerationReviewExecutorImpl(
                reportMapper,
                llmClient,
                contentActionService,
                notificationService,
                properties,
                singleFlightService
        );
    }

    @Test
    void approvedReportTransitionsToApprovedThenAppliesActionAndNotifications() {
        ModerationReport report = pendingReport();
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.decision(
                "opencode",
                "deepseek-v4-flash-free",
                "approved",
                new BigDecimal("0.9300"),
                "policy violation"
        ));
        when(reportMapper.markReviewed(eq(21L), eq("approved"), any(), any(), any(), any(), any())).thenReturn(1);

        executor.review(21L);

        verify(reportMapper).markReviewed(eq(21L), eq("approved"), any(), any(), any(), any(), any());
        verify(contentActionService).applyApprovedAction(report);
        verify(notificationService).notifyReportProcessed(report, true);
        verify(singleFlightService).execute(eq("moderation-llm"), eq("report:21:retry:0"), any(), any(Supplier.class));
    }

    @Test
    void lowConfidenceDecisionBecomesIgnoredWithoutContentAction() {
        ModerationReport report = pendingReport();
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.decision(
                "opencode",
                "deepseek-v4-flash-free",
                "approved",
                new BigDecimal("0.4000"),
                "uncertain"
        ));
        when(reportMapper.markIgnored(eq(21L), eq("opencode"), eq("deepseek-v4-flash-free"), eq("approved"),
                eq(new BigDecimal("0.4000")), eq("uncertain"), eq("LOW_CONFIDENCE"), any(), any()))
                .thenReturn(1);

        executor.review(21L);

        verify(reportMapper).markIgnored(eq(21L), eq("opencode"), eq("deepseek-v4-flash-free"), eq("approved"),
                eq(new BigDecimal("0.4000")), eq("uncertain"), eq("LOW_CONFIDENCE"), any(), any());
        verify(contentActionService, never()).applyApprovedAction(any());
        verify(notificationService).notifyReportProcessed(report, false);
    }

    @Test
    void retryableFailureBeforeMaxRetriesKeepsPendingAndSchedulesRetry() {
        ModerationReport report = pendingReport();
        report.setRetryCount(1);
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.retryableFailure("TIMEOUT", "model timeout"));

        executor.review(21L);

        verify(reportMapper).scheduleRetry(eq(21L), eq(1), eq(2), any(), eq("TIMEOUT"), eq("model timeout"), any());
        verify(singleFlightService).execute(eq("moderation-llm"), eq("report:21:retry:1"), any(), any(Supplier.class));
        verify(reportMapper, never()).markIgnored(eq(21L), any(), any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).notifyReportProcessed(any(), org.mockito.Mockito.anyBoolean());
    }

    @Test
    void retryableFailureAtMaxRetriesBecomesIgnored() {
        ModerationReport report = pendingReport();
        report.setRetryCount(3);
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.retryableFailure("TIMEOUT", "model timeout"));
        when(reportMapper.markIgnored(eq(21L), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("TIMEOUT"), eq("model timeout"), any())).thenReturn(1);

        executor.review(21L);

        verify(reportMapper).markIgnored(eq(21L), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("TIMEOUT"), eq("model timeout"), any());
        verify(notificationService).notifyReportProcessed(report, false);
    }

    @Test
    void ignoredTransitionLosingRaceDoesNotNotify() {
        ModerationReport report = pendingReport();
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.invalidFailure("INVALID_RESPONSE", "bad json"));
        when(reportMapper.markIgnored(eq(21L), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("INVALID_RESPONSE"), eq("bad json"), any())).thenReturn(0);

        executor.review(21L);

        verify(notificationService, never()).notifyReportProcessed(any(), org.mockito.Mockito.anyBoolean());
    }

    @Test
    void notificationFailureIsRecordedWithoutEscapingTerminalReview() {
        ModerationReport report = pendingReport();
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.decision(
                "opencode",
                "deepseek-v4-flash-free",
                "rejected",
                new BigDecimal("0.9300"),
                "no violation"
        ));
        when(reportMapper.markReviewed(eq(21L), eq("rejected"), any(), any(), any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("notification db down"))
                .when(notificationService).notifyReportProcessed(report, false);

        executor.review(21L);

        verify(reportMapper).updateNotificationFailure(eq(21L),
                org.mockito.Mockito.contains("notification db down"),
                any());
    }

    @Test
    void terminalReportIsSkipped() {
        ModerationReport report = pendingReport();
        report.setStatus("approved");
        when(reportMapper.findById(21L)).thenReturn(report);

        executor.review(21L);

        verify(llmClient, never()).review(any());
    }

    @Test
    void retryableFailureReasonTruncationPreservesSurrogatePairs() {
        ModerationReport report = pendingReport();
        String reason = "a".repeat(511) + "\uD83D\uDE00";
        when(reportMapper.findById(21L)).thenReturn(report);
        when(llmClient.review(report)).thenReturn(ModerationLlmResult.retryableFailure("TIMEOUT", reason));

        executor.review(21L);

        org.mockito.ArgumentCaptor<String> reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reportMapper).scheduleRetry(eq(21L), eq(0), eq(1), any(), eq("TIMEOUT"), reasonCaptor.capture(), any());
        assertThat(reasonCaptor.getValue()).endsWith("\uD83D\uDE00");
        assertThat(reasonCaptor.getValue()).hasSize(513);
    }

    @Test
    void scheduleRetryMapperSqlUsesSourceRetryCountCas() throws Exception {
        String mapperXml = Files.readString(Path.of("src/main/resources/mapper/ModerationReportMapper.xml"));
        String scheduleRetrySql = mapperXml.substring(
                mapperXml.indexOf("<update id=\"scheduleRetry\">"),
                mapperXml.indexOf("</update>", mapperXml.indexOf("<update id=\"scheduleRetry\">"))
        );

        assertThat(scheduleRetrySql).contains("AND retry_count = #{sourceRetryCount}");
    }

    private ModerationReport pendingReport() {
        return ModerationReport.builder()
                .id(21L)
                .reporterUserId(7L)
                .targetType("post")
                .targetId(101L)
                .targetOwnerUserId(8L)
                .status("pending")
                .retryCount(0)
                .build();
    }
}
