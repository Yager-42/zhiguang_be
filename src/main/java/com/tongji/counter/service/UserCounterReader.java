package com.tongji.counter.service;

import java.util.Optional;

/**
 * User-counter read seam.
 *
 * <p>{@link #find(long)} only reads existing facts. {@link #getVerified(long)} applies the
 * sampled verification policy and coordinates a rebuild when required.</p>
 */
public interface UserCounterReader {
    /**
     * 读取现有用户计数快照，不触发重建。
     *
     * @param userId 用户 ID
     * @return 快照不存在时返回空值
     */
    Optional<UserCounters> find(long userId);

    /**
     * 按采样策略校验用户计数，必要时协调单飞重建。
     *
     * @param userId 用户 ID
     * @return 当前用户计数
     */
    UserCounters getVerified(long userId);

}
