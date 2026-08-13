package com.tongji.counter.service;

import java.util.Optional;

/**
 * User-counter read seam.
 *
 * <p>{@link #find(long)} only reads existing facts. {@link #getVerified(long)} applies the
 * sampled verification policy and coordinates a rebuild when required.</p>
 */
public interface UserCounterReader {
    Optional<UserCounters> find(long userId);

    UserCounters getVerified(long userId);
}
