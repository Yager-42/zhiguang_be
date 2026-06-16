package com.tongji.common.resilience;

import java.util.function.Predicate;
import java.util.function.Supplier;

public interface ResilienceGuard {

    <T> GuardResult<T> execute(String resourceName,
                               GuardedOperation<T> operation,
                               Supplier<T> fallbackSupplier,
                               Predicate<Throwable> systemFailureClassifier);
}
