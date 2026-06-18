package com.tongji.knowpost.service;

import com.tongji.knowpost.api.dto.FeedPageResponse;

/**
 * 知文 Feed 业务接口。
 */
public interface KnowPostFeedService {
    enum FeedVisibilityScope {
        FOLLOW,
        PUBLIC
    }

    FeedPageResponse getPublicFeed(int page, int size, Long currentUserIdNullable);

    java.util.List<com.tongji.knowpost.api.dto.FeedItemResponse> getFeedByIds(
            java.util.List<Long> ids,
            Long currentUserIdNullable,
            FeedVisibilityScope scope
    );

    FeedPageResponse getMyPublished(long userId, int page, int size);
}
