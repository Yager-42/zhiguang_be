package com.tongji.recommendation.feed;

public interface FollowFeedService {
    TimelinePage getTimeline(long userId, String cursor, int limit);
}
