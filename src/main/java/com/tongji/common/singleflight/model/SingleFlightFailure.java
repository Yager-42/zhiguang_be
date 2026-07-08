package com.tongji.common.singleflight.model;

public record SingleFlightFailure(
        SingleFlightErrorType errorType,
        String errorCode,
        boolean retryable
) {
}
