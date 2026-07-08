package com.tongji.common.singleflight;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class LocalSingleFlightService {

    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String stage, String requestKey, Supplier<T> supplier) {
        String key = stage + "|" + requestKey;
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, created);
        if (existing == null) {
            try {
                T result = supplier.get();
                created.complete(result);
                return result;
            } catch (Throwable throwable) {
                created.completeExceptionally(throwable);
                throw rethrow(throwable);
            } finally {
                inFlight.remove(key, created);
            }
        }
        try {
            return (T) existing.join();
        } catch (CompletionException exception) {
            throw exception;
        }
    }

    private RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(throwable);
    }
}
