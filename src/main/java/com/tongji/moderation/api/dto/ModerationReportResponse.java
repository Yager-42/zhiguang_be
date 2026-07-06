package com.tongji.moderation.api.dto;

/**
 * 举报响应。reportId 用 String 序列化（snowflake 64 位 >2^53，防 JS 精度丢失）。
 */
public record ModerationReportResponse(
        String reportId,
        String status
) {
}
