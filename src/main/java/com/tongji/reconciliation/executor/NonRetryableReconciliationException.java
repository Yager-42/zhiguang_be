package com.tongji.reconciliation.executor;

public class NonRetryableReconciliationException extends RuntimeException {
    public NonRetryableReconciliationException(String message) {
        super(message);
    }

    public NonRetryableReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}
