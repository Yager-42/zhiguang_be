package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedPublisher;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.text.TextStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Service
public class PublishManagerImpl implements PublishManager {

    private static final Logger log = LoggerFactory.getLogger(PublishManagerImpl.class);

    private final PublishAttemptService publishAttemptService;
    private final ContentPublishedPublisher contentPublishedPublisher;
    private final UserCounterService userCounterService;
    private final ResilienceGuard resilienceGuard;
    private final TaskExecutor publishExecutor;
    private final TaskExecutor reconciliationExecutor;
    private final TextStorageService textStorageService;
    private final KnowPostMapper knowPostMapper;
    private final MinioStorageService minioStorageService;
    private final RestTemplate restTemplate;

    @Autowired
    public PublishManagerImpl(PublishAttemptService publishAttemptService,
                              ContentPublishedPublisher contentPublishedPublisher,
                              UserCounterService userCounterService,
                              ResilienceGuard resilienceGuard,
                              @Qualifier("publishExecutor") TaskExecutor publishExecutor,
                              @Qualifier("reconciliationExecutor") TaskExecutor reconciliationExecutor,
                              TextStorageService textStorageService,
                              KnowPostMapper knowPostMapper,
                              MinioStorageService minioStorageService) {
        this(publishAttemptService,
                contentPublishedPublisher,
                userCounterService,
                resilienceGuard,
                publishExecutor,
                reconciliationExecutor,
                textStorageService,
                knowPostMapper,
                minioStorageService,
                new RestTemplate());
    }

    PublishManagerImpl(PublishAttemptService publishAttemptService,
                       ContentPublishedPublisher contentPublishedPublisher,
                       UserCounterService userCounterService,
                       ResilienceGuard resilienceGuard,
                       TaskExecutor publishExecutor,
                       TaskExecutor reconciliationExecutor,
                       TextStorageService textStorageService,
                       KnowPostMapper knowPostMapper,
                       MinioStorageService minioStorageService,
                       RestTemplate restTemplate) {
        this.publishAttemptService = Objects.requireNonNull(publishAttemptService, "publishAttemptService");
        this.contentPublishedPublisher = Objects.requireNonNull(contentPublishedPublisher, "contentPublishedPublisher");
        this.userCounterService = Objects.requireNonNull(userCounterService, "userCounterService");
        this.resilienceGuard = Objects.requireNonNull(resilienceGuard, "resilienceGuard");
        this.publishExecutor = Objects.requireNonNull(publishExecutor, "publishExecutor");
        this.reconciliationExecutor = Objects.requireNonNull(reconciliationExecutor, "reconciliationExecutor");
        this.textStorageService = Objects.requireNonNull(textStorageService, "textStorageService");
        this.knowPostMapper = Objects.requireNonNull(knowPostMapper, "knowPostMapper");
        this.minioStorageService = Objects.requireNonNull(minioStorageService, "minioStorageService");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public PublishAcceptedResponse acceptPublish(long authorId, long postId, String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "幂等键不能为空");
        }
        PublishAcceptance acceptance = publishAttemptService.acceptPublish(authorId, postId, idempotentKey);
        PublishAttempt attempt = acceptance.attempt();
        if (acceptance.schedulePublishWork()) {
            submitPublish(authorId, postId, attempt.getAttemptId());
        }
        return new PublishAcceptedResponse(String.valueOf(attempt.getAttemptId()));
    }

    @Override
    public PublishAcceptedResponse retryPublish(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptService.retryPublish(authorId, postId, attemptId);
        submitPublish(authorId, postId, attempt.getAttemptId());
        return new PublishAcceptedResponse(String.valueOf(attempt.getAttemptId()));
    }

    @Override
    public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
        return publishAttemptService.getPublishStatus(authorId, postId, attemptId);
    }

    @Override
    @Scheduled(fixedDelay = 60000)
    public int recoverStuckPublishing() {
        CompletableFuture<Integer> recoveredFuture = new CompletableFuture<>();
        reconciliationExecutor.execute(() -> {
            try {
                recoveredFuture.complete(publishAttemptService.recoverStuckPublishingAttempts());
            } catch (Throwable throwable) {
                recoveredFuture.completeExceptionally(throwable);
            }
        });
        return recoveredFuture.join();
    }

    private void submitPublish(long authorId, long postId, long attemptId) {
        publishExecutor.execute(() -> runPublish(authorId, postId, attemptId));
    }

    private void runPublish(long authorId, long postId, long attemptId) {
        try {
            storePostText(postId);
            publishAttemptService.completePublish(authorId, postId, attemptId);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                publishAttemptService.failPublish(authorId, postId, attemptId, "critical_publish", message);
            } catch (Exception failEx) {
                log.warn("Failing publish attempt {} also failed: {}", attemptId, failEx.getMessage());
            }
            return;
        }

        reconciliationExecutor.execute(() -> runDerivedPublishWork(authorId, postId, attemptId));
    }

    private void storePostText(long postId) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知文不存在");
        }
        String contentUrl = resolveContentUrl(post);
        String body = restTemplate.getForObject(contentUrl, String.class);
        if (body == null) {
            throw new IllegalStateException("Fetched post body is empty");
        }
        textStorageService.savePostText(postId, body, post.getContentSha256());
    }

    private String resolveContentUrl(KnowPost post) {
        if (post.getContentUrl() != null && !post.getContentUrl().isBlank()) {
            return post.getContentUrl();
        }
        if (post.getContentObjectKey() != null && !post.getContentObjectKey().isBlank()) {
            return minioStorageService.publicUrl(post.getContentObjectKey());
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "正文内容地址不存在");
    }

    private void runDerivedPublishWork(long authorId, long postId, long attemptId) {
        var guardResult = resilienceGuard.execute(
                "publish:user-counter",
                () -> {
                    userCounterService.incrementPosts(authorId, 1);
                    return null;
                },
                () -> null,
                this::isSystemFailure
        );
        if (guardResult.fallbackApplied()) {
            handleDerivedFailure(attemptId, postId, guardResult.failure());
        }
    }

    private boolean isSystemFailure(Throwable throwable) {
        return !(throwable instanceof BusinessException);
    }

    private void handleDerivedFailure(long attemptId, long postId, Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
                ? throwable == null ? "Unknown failure" : throwable.getClass().getSimpleName()
                : throwable.getMessage();
        try {
            contentPublishedPublisher.publishDerivedFailure(
                    "user_counter_increment",
                    "knowpost",
                    postId,
                    message,
                    Instant.now().plusSeconds(300)
            );
        } catch (Exception publishFailure) {
            publishAttemptService.recordDerivedFailureFallback(
                    attemptId,
                    "user_counter_increment",
                    "knowpost",
                    postId,
                    message + "; durable publish failed: " + publishFailure.getMessage(),
                    Instant.now().plusSeconds(300)
            );
        }
    }
}
