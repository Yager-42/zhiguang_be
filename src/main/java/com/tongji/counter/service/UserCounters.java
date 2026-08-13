package com.tongji.counter.service;

/**
 * Immutable user-level counter facts.
 *
 * <p>The Redis SDS layout is an implementation detail of the counter module.</p>
 */
public record UserCounters(
        long followings,
        long followers,
        long posts,
        long likedPosts,
        long favedPosts
) {
    public static UserCounters zero() {
        return new UserCounters(0L, 0L, 0L, 0L, 0L);
    }
}
