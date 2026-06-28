package com.tongji.moderation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ModerationReportRequest(
        @NotBlank(message = "targetType is required")
        String targetType,
        @NotNull(message = "targetId is required")
        @Positive(message = "targetId must be positive")
        Long targetId,
        @NotBlank(message = "reason is required")
        String reason,
        @Size(max = 512, message = "description too long")
        String description
) {
}
