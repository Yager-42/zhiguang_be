package com.tongji.moderation.schedule;

import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.ModerationReviewExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "moderation.llm", name = "enabled", havingValue = "true")
public class ModerationReviewRetryJob {
    private static final int BATCH_SIZE = 50;

    private final ModerationReportMapper reportMapper;
    private final ModerationReviewExecutor executor;

    public ModerationReviewRetryJob(ModerationReportMapper reportMapper,
                                    ModerationReviewExecutor executor) {
        this.reportMapper = reportMapper;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${moderation.llm.retry-fixed-delay-ms:60000}")
    public void retryDueReports() {
        for (ModerationReport report : reportMapper.listDueRetries(LocalDateTime.now(), BATCH_SIZE)) {
            try {
                executor.review(report.getId());
            } catch (RuntimeException exception) {
                log.warn("moderation retry review failed, reportId={}: {}", report.getId(), exception.getMessage(), exception);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        }
    }
}
