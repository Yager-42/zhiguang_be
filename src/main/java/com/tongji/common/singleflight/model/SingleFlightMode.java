package com.tongji.common.singleflight.model;

public enum SingleFlightMode {
    DISABLED,
    LOCAL,
    DISTRIBUTED,
    HYBRID;

    public static SingleFlightMode from(String value) {
        if (value == null || value.isBlank()) {
            return DISTRIBUTED;
        }
        for (SingleFlightMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return DISTRIBUTED;
    }
}
