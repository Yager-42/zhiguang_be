package com.tongji.common.singleflight.model;

public record SingleFlightPolicy(
        long runningTtlMillis,
        long resultTtlMillis,
        long failedResultTtlMillis,
        long followerMaxWaitMillis,
        long streamBlockTimeoutMillis,
        long pollFallbackIntervalMillis,
        long takeoverDetectMillis,
        long heartbeatIntervalMillis,
        boolean l1CacheEnabled,
        long l1CacheTtlMillis,
        int l1CacheMaxSize,
        int compressionThresholdBytes,
        String compressionCodec
) {
}
