package com.tongji.knowpost.manager;

import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;

public interface PublishManager {
    PublishAcceptedResponse acceptPublish(long authorId, long postId, String idempotentKey);

    PublishAcceptedResponse retryPublish(long authorId, long postId, long attemptId);

    PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId);

    int recoverStuckPublishing();
}
