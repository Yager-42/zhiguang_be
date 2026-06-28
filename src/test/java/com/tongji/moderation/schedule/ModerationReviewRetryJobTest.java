package com.tongji.moderation.schedule;

import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.ModerationReviewExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationReviewRetryJobTest {
    private ModerationReportMapper reportMapper;
    private ModerationReviewExecutor executor;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ModerationReportMapper.class);
        executor = mock(ModerationReviewExecutor.class);
    }

    @Test
    void oneFailedRetryDoesNotBlockRemainingDueReports() {
        ModerationReviewRetryJob job = new ModerationReviewRetryJob(reportMapper, executor);
        when(reportMapper.listDueRetries(any(), eq(50))).thenReturn(List.of(report(21L), report(22L)));
        doThrow(new IllegalStateException("db down")).when(executor).review(21L);

        job.retryDueReports();

        verify(executor).review(21L);
        verify(executor).review(22L);
    }

    @Test
    void interruptedRetryStopsBatch() {
        ModerationReviewRetryJob job = new ModerationReviewRetryJob(reportMapper, executor);
        when(reportMapper.listDueRetries(any(), eq(50))).thenReturn(List.of(report(21L), report(22L)));
        doThrow(new IllegalStateException("moderation review lock interrupted")).when(executor).review(21L);

        Thread.currentThread().interrupt();
        try {
            job.retryDueReports();

            verify(executor).review(21L);
            verify(executor, never()).review(22L);
        } finally {
            Thread.interrupted();
        }
    }

    private ModerationReport report(long id) {
        return ModerationReport.builder()
                .id(id)
                .status("pending")
                .build();
    }
}
