package com.tongji.common.singleflight.model;

public record SingleFlightStoredResult(
        String payload,
        String codec,
        boolean compressed,
        int rawSize,
        int storedSize,
        String checksum,
        String contentType,
        long finishedAt,
        Long ownerToken
) {
}
