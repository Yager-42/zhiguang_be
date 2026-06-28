package com.tongji.moderation.model;

import java.math.BigDecimal;

public record ModerationLlmResult(
        boolean retryableFailure,
        String failureCode,
        String failureReason,
        String provider,
        String model,
        String decision,
        BigDecimal confidence,
        String summary
) {
    public static ModerationLlmResult decision(String provider,
                                               String model,
                                               String decision,
                                               BigDecimal confidence,
                                               String summary) {
        return new ModerationLlmResult(false, null, null, provider, model, decision, confidence, summary);
    }

    public static ModerationLlmResult retryableFailure(String failureCode, String failureReason) {
        return new ModerationLlmResult(true, failureCode, failureReason, null, null, null, null, null);
    }

    public static ModerationLlmResult retryableFailure(String provider, String model, String failureCode, String failureReason) {
        return new ModerationLlmResult(true, failureCode, failureReason, provider, model, null, null, null);
    }

    public static ModerationLlmResult invalidFailure(String failureCode, String failureReason) {
        return new ModerationLlmResult(false, failureCode, failureReason, null, null, null, null, null);
    }

    public static ModerationLlmResult invalidFailure(String provider, String model, String failureCode, String failureReason) {
        return new ModerationLlmResult(false, failureCode, failureReason, provider, model, null, null, null);
    }
}
