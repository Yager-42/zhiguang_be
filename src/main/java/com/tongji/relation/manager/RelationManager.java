package com.tongji.relation.manager;

import java.util.Map;

public interface RelationManager {

    RelationWriteResult follow(long fromUserId, long toUserId);

    RelationWriteResult unfollow(long fromUserId, long toUserId);

    Map<String, Boolean> status(long userId, long otherUserId);

    /** 是否存在有效关注关系 */
    boolean isFollowing(long fromUserId, long toUserId);
}
