package com.tongji.common.resilience;

@FunctionalInterface
public interface GuardedOperation<T> {

    T execute() throws Exception;
}
