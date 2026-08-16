package com.tongji.recommendation.feed;

public interface FollowFeedService {
    TimelinePage getTimeline(long userId, String cursor, int limit);

    /**
     * 使指定用户的关注流 timeline 缓存失效（关注/取关后调用，避免新关系最长 5 分钟内不可见）。
     */
    void invalidateTimelineCache(long userId);

    /**
     * 使指定作者的作者时间线头部缓存失效（关注补偿回填后调用）。
     */
    void invalidateAuthorHeadCache(long authorId);
}
