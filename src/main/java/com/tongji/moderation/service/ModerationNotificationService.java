package com.tongji.moderation.service;

import com.tongji.moderation.model.ModerationReport;

public interface ModerationNotificationService {
    void notifyReportProcessed(ModerationReport report, boolean contentActionApplied);
}
