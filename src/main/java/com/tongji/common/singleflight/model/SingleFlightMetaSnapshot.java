package com.tongji.common.singleflight.model;

public record SingleFlightMetaSnapshot(
        String stage,
        SingleFlightStatus status,
        String ownerId,
        Long ownerToken,
        Long heartbeatAt,
        boolean retryable,
        SingleFlightErrorType errorType,
        String errorCode
) {
}
