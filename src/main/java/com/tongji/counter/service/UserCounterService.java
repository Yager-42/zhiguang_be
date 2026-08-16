package com.tongji.counter.service;

/**
 * 用户维度计数服务接口。
 *
 * <p>支持维护关注数、粉丝数、发文数、获赞数、获收藏数，并提供全量重建。</p>
 */
public interface UserCounterService {
    /**
     * 基于 following/follower 事实重建关注数与粉丝数（幂等）。
     *
     * <p>关系事件处理使用本方法替代增量累加：无论事件去重、乱序还是部分执行，
     * 只要事件最终落地一次，计数即收敛到数据库事实，不会出现丢失增量。</p>
     */
    void rebuildFollowCounters(long fromUserId, long toUserId);
    /** 增量更新发文数 */
    void incrementPosts(long userId, int delta);
    /** 增量更新获赞数（作者维度） */
    void incrementLikesReceived(long userId, int delta);
    /** 增量更新获收藏数（作者维度） */
    void incrementFavsReceived(long userId, int delta);
    /** 基于事实重建全部计数 */
    void rebuildAllCounters(long userId);
}

