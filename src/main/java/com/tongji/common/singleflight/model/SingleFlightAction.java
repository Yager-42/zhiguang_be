package com.tongji.common.singleflight.model;

public enum SingleFlightAction {
    OWNER_NEW,
    OWNER_TAKEOVER,
    FOLLOWER_WAIT,
    REPLAY_SUCCESS,
    REPLAY_FAILURE;

    public static SingleFlightAction from(String value) {
        if (value == null || value.isBlank()) {
            return FOLLOWER_WAIT;
        }
        for (SingleFlightAction action : values()) {
            if (action.name().equalsIgnoreCase(value.trim())) {
                return action;
            }
        }
        return FOLLOWER_WAIT;
    }
}
