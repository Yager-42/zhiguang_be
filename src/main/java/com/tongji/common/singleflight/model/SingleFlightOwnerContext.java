package com.tongji.common.singleflight.model;

public record SingleFlightOwnerContext(
        String stage,
        String requestKey,
        String ownerId,
        Long ownerToken,
        SingleFlightPolicy policy
) {
}
