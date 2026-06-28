package com.tongji.moderation.api.dto;

public record ModerationReportResponse(
        Long reportId,
        String status
) {
}
