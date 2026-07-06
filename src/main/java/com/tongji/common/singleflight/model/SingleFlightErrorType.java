package com.tongji.common.singleflight.model;

public enum SingleFlightErrorType {
    TIMEOUT,
    OVERLOAD,
    PROVIDER,
    VALIDATION,
    UNEXPECTED;

    public static SingleFlightErrorType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SingleFlightErrorType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return UNEXPECTED;
    }
}
