package com.tongji.common.singleflight.model;

public enum SingleFlightStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static SingleFlightStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SingleFlightStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}
