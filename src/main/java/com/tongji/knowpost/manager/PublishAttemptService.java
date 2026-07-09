package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedEvent;
import com.tongji.knowpost.publish.ContentPublishedPublisher;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.wallet.service.ContentRewardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class PublishAttemptService {

    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(5);
    private static final String STUCK_MESSAGE = "Publishing timed out after 5 minutes";

    private final KnowPostMapper knowPostMapper;
    private final PublishAttemptMapper publishAttemptMapper;
    private final PublishValidationHelper publishValidationHelper;
    private final IdService idService;
    private final ContentPublishedPublisher contentPublishedPublisher;
    private final ResilienceGuard resilienceGuard;
    private final Clock clock;
    private final ContentRewardService contentRewardService;

    @Autowired
    public PublishAttemptService(KnowPostMapper knowPostMapper,
                                 PublishAttemptMapper publishAttemptMapper,
                                 PublishValidationHelper publishValidationHelper,
                                 IdService idService,
                                 ContentPublishedPublisher contentPublishedPublisher,
                                 ResilienceGuard resilienceGuard,
                                 ContentRewardService contentRewardService) {
        this(knowPostMapper, publishAttemptMapper, publishValidationHelper, idService, contentPublishedPublisher, resilienceGuard, Clock.systemUTC(), contentRewardService);
    }

    PublishAttemptService(KnowPostMapper knowPostMapper,
                          PublishAttemptMapper publishAttemptMapper,
                          PublishValidationHelper publishValidationHelper,
                          IdService idService,
                          ContentPublishedPublisher contentPublishedPublisher,
                          ResilienceGuard resilienceGuard,
                          Clock clock,
                          ContentRewardService contentRewardService) {
        this.knowPostMapper = knowPostMapper;
        this.publishAttemptMapper = publishAttemptMapper;
        this.publishValidationHelper = publishValidationHelper;
        this.idService = idService;
        this.contentPublishedPublisher = contentPublishedPublisher;
        this.resilienceGuard = resilienceGuard;
        this.clock = clock;
        this.contentRewardService = contentRewardService;
    }

    @Transactional
    public PublishAcceptance acceptPublish(long authorId, long postId, String idempotentKey) {
        PublishAttempt existing = publishAttemptMapper.findByIdempotencyKey(authorId, postId, idempotentKey);
        if (existing != null) {
            return new PublishAcceptance(existing, false);
        }

        KnowPost post = publishValidationHelper.loadOwnedPost(authorId, postId);
        publishValidationHelper.requirePublishableDraft(post);

        Instant now = Instant.now(clock);
        PublishAttempt attempt = PublishAttempt.builder()
                .attemptId(idService.nextId(IdNamespace.PUBLISH_ATTEMPT))
                .postId(postId)
                .creatorId(authorId)
                .idempotentKey(idempotentKey)
                .status("publishing")
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            publishAttemptMapper.insert(attempt);
        } catch (DuplicateKeyException duplicateKeyException) {
            PublishAttempt racedAttempt = publishAttemptMapper.findByIdempotencyKey(authorId, postId, idempotentKey);
            if (racedAttempt != null) {
                return new PublishAcceptance(racedAttempt, false);
            }
            throw duplicateKeyException;
        }
        if (knowPostMapper.startPublishing(postId, authorId, attempt.getAttemptId()) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不可发布");
        }
        return new PublishAcceptance(attempt, true);
    }

    @Transactional
    public PublishAttempt retryPublish(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);

        KnowPost post = knowPostMapper.findPublishStatus(postId, authorId);
        recoverStuckPublishingIfNeeded(post, attempt);
        publishValidationHelper.requireRetryable(post, attempt);

        if (knowPostMapper.retryPublishing(postId, authorId, attemptId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }
        if (publishAttemptMapper.restartFailedAttempt(attemptId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }

        attempt.setStatus("publishing");
        attempt.setRetryCount((attempt.getRetryCount() == null ? 0 : attempt.getRetryCount()) + 1);
        attempt.setFailedStep(null);
        attempt.setErrorMessage(null);
        attempt.setUpdatedAt(Instant.now(clock));
        return attempt;
    }

    @Transactional
    public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);

        KnowPost post = knowPostMapper.findPublishStatus(postId, authorId);
        recoverStuckPublishingIfNeeded(post, attempt);

        return new PublishStatusResponse(
                String.valueOf(attempt.getAttemptId()),
                attempt.getStatus(),
                post.getStatus(),
                attempt.getFailedStep(),
                isRetryable(attempt, post)
        );
    }

    @Transactional
    public void completePublish(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);

        KnowPost post = publishValidationHelper.loadOwnedPost(authorId, postId);
        publishValidationHelper.requirePublishing(post, attempt);

        GuardResult<Void> publishResult = resilienceGuard.execute(
                "publish:content-published",
                () -> {
                    contentPublishedPublisher.publish(new ContentPublishedEvent(
                            postId,
                            authorId,
                            attemptId,
                            Instant.now(clock)
                    ));
                    return null;
                },
                () -> null,
                this::isSystemFailure
        );
        requireCriticalGuardSuccess(publishResult);

        if (knowPostMapper.completePublish(postId, authorId, attemptId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布状态已变更");
        }
        if (publishAttemptMapper.markSucceeded(attemptId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试状态已变更");
        }
        // 发布成功后发积分奖励。REQUIRES_NEW 独立事务 + catch 在 service 内，失败不阻塞发布主流程。
        contentRewardService.rewardPostCreation(authorId, postId);
    }

    @Transactional
    public void failPublish(long authorId, long postId, long attemptId, String failedStep, String errorMessage) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);

        knowPostMapper.failPublish(postId, authorId, attemptId, errorMessage);
        publishAttemptMapper.markFailed(attemptId, failedStep, errorMessage);
    }

    @Transactional
    public int recoverStuckPublishingAttempts() {
        List<PublishAttempt> stuckAttempts = publishAttemptMapper.findStuckPublishingAttempts(Instant.now(clock).minus(STUCK_TIMEOUT));
        int recovered = 0;
        for (PublishAttempt attempt : stuckAttempts) {
            KnowPost post = knowPostMapper.findPublishStatus(attempt.getPostId(), attempt.getCreatorId());
            boolean alreadyRecovered = !"publishing".equals(attempt.getStatus());
            recoverStuckPublishingIfNeeded(post, attempt);
            if (!alreadyRecovered && "failed".equals(attempt.getStatus())) {
                recovered++;
            }
        }
        return recovered;
    }

    @Transactional
    public void recordDerivedFailureFallback(long attemptId,
                                             String taskType,
                                             String targetType,
                                             long targetId,
                                             String failureReason,
                                             Instant nextRetryHint) {
        if (publishAttemptMapper.updateDerivedFailureFallback(
                attemptId,
                taskType,
                targetType,
                targetId,
                failureReason,
                nextRetryHint
        ) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试不存在");
        }
    }

    private void recoverStuckPublishingIfNeeded(KnowPost post, PublishAttempt attempt) {
        if (post == null || attempt == null) {
            return;
        }
        if (!"publishing".equals(post.getStatus()) || !"publishing".equals(attempt.getStatus())) {
            return;
        }
        Instant updatedAt = attempt.getUpdatedAt() != null ? attempt.getUpdatedAt() : post.getUpdateTime();
        if (updatedAt == null || !updatedAt.isBefore(Instant.now(clock).minus(STUCK_TIMEOUT))) {
            return;
        }

        if (knowPostMapper.failPublish(post.getId(), post.getCreatorId(), attempt.getAttemptId(), STUCK_MESSAGE) > 0) {
            publishAttemptMapper.markFailed(attempt.getAttemptId(), "stuck_publishing", STUCK_MESSAGE);
            post.setStatus("publish_failed");
            post.setPublishFailedReason(STUCK_MESSAGE);
            attempt.setStatus("failed");
            attempt.setFailedStep("stuck_publishing");
            attempt.setErrorMessage(STUCK_MESSAGE);
            attempt.setUpdatedAt(Instant.now(clock));
        }
    }

    private boolean isRetryable(PublishAttempt attempt, KnowPost post) {
        return "failed".equals(attempt.getStatus()) && "publish_failed".equals(post.getStatus());
    }

    private boolean isSystemFailure(Throwable throwable) {
        return !(throwable instanceof BusinessException);
    }

    private void requireCriticalGuardSuccess(GuardResult<Void> guardResult) {
        if (!guardResult.fallbackApplied()) {
            return;
        }
        Throwable failure = guardResult.failure();
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("content published event degraded", failure);
    }
}
