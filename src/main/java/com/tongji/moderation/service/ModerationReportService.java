package com.tongji.moderation.service;

import com.tongji.moderation.api.dto.ModerationReportRequest;
import com.tongji.moderation.api.dto.ModerationReportResponse;

public interface ModerationReportService {
    ModerationReportResponse submitReport(long reporterUserId, ModerationReportRequest request);
}
