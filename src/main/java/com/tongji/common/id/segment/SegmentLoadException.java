package com.tongji.common.id.segment;

public class SegmentLoadException extends RuntimeException {
    public SegmentLoadException(String message) {
        super(message);
    }

    public SegmentLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
