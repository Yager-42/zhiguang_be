package com.tongji.common.resilience;

public record GuardResult<T>(T value, boolean fallbackApplied, Throwable failure) {

    public static <T> GuardResult<T> success(T value) {
        return new GuardResult<>(value, false, null);
    }

    public static <T> GuardResult<T> fallback(T value, Throwable failure) {
        return new GuardResult<>(value, true, failure);
    }
}
