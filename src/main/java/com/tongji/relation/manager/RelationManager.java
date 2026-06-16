package com.tongji.relation.manager;

import java.util.Map;

public interface RelationManager {

    RelationWriteResult follow(long fromUserId, long toUserId);

    RelationWriteResult unfollow(long fromUserId, long toUserId);

    Map<String, Boolean> status(long userId, long otherUserId);
}
