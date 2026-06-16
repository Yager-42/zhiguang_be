package com.tongji.common.resilience;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Component
public class SentinelResilienceGuard implements ResilienceGuard {

    private final SentinelTracer tracer;

    public SentinelResilienceGuard() {
        this(Tracer::trace);
    }

    SentinelResilienceGuard(SentinelTracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    @Override
    public <T> GuardResult<T> execute(String resourceName,
                                      GuardedOperation<T> operation,
                                      Supplier<T> fallbackSupplier,
                                      Predicate<Throwable> systemFailureClassifier) {
        Objects.requireNonNull(resourceName, "resourceName must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(fallbackSupplier, "fallbackSupplier must not be null");
        Objects.requireNonNull(systemFailureClassifier, "systemFailureClassifier must not be null");

        Entry entry = null;
        try {
            entry = SphU.entry(resourceName);
            return GuardResult.success(operation.execute());
        } catch (BlockException exception) {
            if (systemFailureClassifier.test(exception)) {
                tracer.trace(exception);
            }
            return GuardResult.fallback(fallbackSupplier.get(), exception);
        } catch (Exception exception) {
            if (systemFailureClassifier.test(exception)) {
                tracer.trace(exception);
            }
            return GuardResult.fallback(fallbackSupplier.get(), exception);
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    @FunctionalInterface
    interface SentinelTracer {
        void trace(Throwable throwable);
    }
}
