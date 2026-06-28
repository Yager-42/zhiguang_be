package com.tongji.moderation.model;

import java.math.BigDecimal;

public record ModerationLlmResponse(
        String decision,
        BigDecimal confidence,
        String summary
) {
}
