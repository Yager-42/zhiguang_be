package com.tongji.common.id;

public class ClockBackwardException extends RuntimeException {

    public ClockBackwardException(String message) {
        super(message);
    }

    public ClockBackwardException(String message, Throwable cause) {
        super(message, cause);
    }
}
