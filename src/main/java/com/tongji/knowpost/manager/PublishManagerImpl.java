package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.publish.PublishAttempt;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 将发布 HTTP 用例编排到持久化 attempt 状态机。
 *
 * <p>关键发布工作由 Kafka Listener 执行，本管理器不持有本地任务队列。</p>
 *
 * @since 2026-08-28
 */
@Service
public class PublishManagerImpl implements PublishManager {

    private final PublishAttemptService publishAttemptService;

    public PublishManagerImpl(PublishAttemptService publishAttemptService) {
        this.publishAttemptService = Objects.requireNonNull(publishAttemptService, "publishAttemptService");
    }

    @Override
    public PublishAcceptedResponse acceptPublish(long authorId, long postId, String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "幂等键不能为空");
        }
        PublishAttempt attempt = publishAttemptService.acceptPublish(authorId, postId, idempotentKey);
        return new PublishAcceptedResponse(String.valueOf(attempt.getAttemptId()));
    }

    @Override
    public PublishAcceptedResponse retryPublish(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptService.retryPublish(authorId, postId, attemptId);
        return new PublishAcceptedResponse(String.valueOf(attempt.getAttemptId()));
    }

    @Override
    public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
        return publishAttemptService.getPublishStatus(authorId, postId, attemptId);
    }
}
