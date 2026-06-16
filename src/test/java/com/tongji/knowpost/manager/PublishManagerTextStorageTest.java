package com.tongji.knowpost.manager;

import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.common.resilience.GuardResult;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedPublisher;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.config.StorageProperties;
import com.tongji.storage.text.TextStorageService;
import com.tongji.storage.text.TextWriteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishManagerTextStorageTest {

    @Mock
    private TextStorageService textStorageService;
    @Mock
    private KnowPostMapper knowPostMapper;

    private StubPublishAttemptService publishAttemptService;
    private StubMinioStorageService minioStorageService;
    private StubRestTemplate restTemplate;
    private PublishManager manager;
    private List<String> callLog;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        callLog = new ArrayList<>();
        publishAttemptService = new StubPublishAttemptService();
        minioStorageService = new StubMinioStorageService();
        restTemplate = new StubRestTemplate(callLog);
        manager = new PublishManagerImpl(
                publishAttemptService,
                new NoOpContentPublishedPublisher(),
                new NoOpUserCounterService(),
                new NoOpResilienceGuard(),
                Runnable::run,
                task -> { },
                textStorageService,
                knowPostMapper,
                minioStorageService,
                restTemplate
        );
        org.mockito.Mockito.doAnswer(invocation -> {
            callLog.add("save:" + invocation.getArgument(0));
            return null;
        }).when(textStorageService).savePostText(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void acceptPublishSavesFetchedTextBeforeCompletingPublish() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        when(knowPostMapper.findById(9L)).thenReturn(KnowPost.builder()
                .id(9L)
                .creatorId(7L)
                .contentUrl("https://cdn.example.com/posts/9.md")
                .contentSha256("sha-9")
                .status("publishing")
                .build());
        restTemplate.setResponseBody("post-body");

        PublishAcceptedResponse response = manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        assertThat(callLog).containsExactly("fetch:https://cdn.example.com/posts/9.md", "save:9", "complete:88");
        assertThat(restTemplate.lastUrl).isEqualTo("https://cdn.example.com/posts/9.md");
        verify(textStorageService).savePostText(9L, "post-body", "sha-9");
        assertThat(minioStorageService.invocationCount).isZero();
    }

    @Test
    void acceptPublishUsesObjectKeyFallbackWhenContentUrlMissing() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        when(knowPostMapper.findById(9L)).thenReturn(KnowPost.builder()
                .id(9L)
                .creatorId(7L)
                .contentObjectKey("posts/9/content.md")
                .contentSha256("sha-9")
                .status("publishing")
                .build());
        minioStorageService.publicUrl = "https://cdn.example.com/public/posts/9/content.md";
        restTemplate.setResponseBody("fallback-body");

        manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(minioStorageService.invocationCount).isEqualTo(1);
        assertThat(minioStorageService.lastObjectKey).isEqualTo("posts/9/content.md");
        verify(textStorageService).savePostText(9L, "fallback-body", "sha-9");
        assertThat(callLog).containsExactly("fetch:https://cdn.example.com/public/posts/9/content.md", "save:9", "complete:88");
        assertThat(restTemplate.lastUrl).isEqualTo("https://cdn.example.com/public/posts/9/content.md");
    }

    @Test
    void acceptPublishMarksAttemptFailedWhenTextWriteFails() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        when(knowPostMapper.findById(9L)).thenReturn(KnowPost.builder()
                .id(9L)
                .creatorId(7L)
                .contentUrl("https://cdn.example.com/posts/9.md")
                .contentSha256("sha-9")
                .status("publishing")
                .build());
        restTemplate.setResponseBody("post-body");
        doThrow(new TextWriteException("cassandra unavailable"))
                .when(textStorageService)
                .savePostText(9L, "post-body", "sha-9");

        PublishAcceptedResponse response = manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        assertThat(publishAttemptService.completedCalls).isZero();
        assertThat(callLog).containsExactly("fetch:https://cdn.example.com/posts/9.md", "fail:88");
        assertThat(publishAttemptService.failedAttemptId).isEqualTo(88L);
        assertThat(publishAttemptService.failedStep).isEqualTo("critical_publish");
        assertThat(publishAttemptService.failedMessage).isEqualTo("cassandra unavailable");
    }

    @Test
    void managerOnlyWritesTextDuringAcceptedPublishFlow() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), false);
        publishAttemptService.statusResult = new PublishStatusResponse("88", "publishing", null, null, true);
        PublishManager directManager = new PublishManagerImpl(
                publishAttemptService,
                new NoOpContentPublishedPublisher(),
                new NoOpUserCounterService(),
                new NoOpResilienceGuard(),
                Runnable::run,
                Runnable::run,
                textStorageService,
                knowPostMapper,
                minioStorageService,
                restTemplate
        );

        directManager.getPublishStatus(7L, 9L, 88L);
        directManager.recoverStuckPublishing();
        PublishAcceptedResponse response = directManager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        verifyNoInteractions(textStorageService);
        assertThat(callLog).isEmpty();
        assertThat(publishAttemptService.completedCalls).isZero();
    }

    private static PublishAttempt attempt(Long attemptId, Long postId, Long creatorId) {
        return PublishAttempt.builder()
                .attemptId(attemptId)
                .postId(postId)
                .creatorId(creatorId)
                .status("publishing")
                .retryCount(0)
                .build();
    }

    private static final class StubRestTemplate extends RestTemplate {
        private final List<String> callLog;
        private String responseBody;
        private RuntimeException exception;
        private String lastUrl;

        private StubRestTemplate(List<String> callLog) {
            this.callLog = callLog;
        }

        void setResponseBody(String responseBody) {
            this.responseBody = responseBody;
        }

        @SuppressWarnings("unused")
        void setException(RuntimeException exception) {
            this.exception = exception;
        }

        @Override
        public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) throws RestClientException {
            lastUrl = url;
            callLog.add("fetch:" + url);
            if (exception != null) {
                throw exception;
            }
            return responseType.cast(responseBody);
        }
    }

    private static final class StubMinioStorageService extends MinioStorageService {
        private String publicUrl;
        private int invocationCount;
        private String lastObjectKey;

        private StubMinioStorageService() {
            super(new StorageProperties());
        }

        @Override
        public String publicUrl(String objectKey) {
            invocationCount++;
            lastObjectKey = objectKey;
            return publicUrl;
        }
    }

    private static final class NoOpContentPublishedPublisher extends ContentPublishedPublisher {
        private NoOpContentPublishedPublisher() {
            super(null, null, null);
        }
    }

    private static final class NoOpUserCounterService implements UserCounterService {
        @Override
        public void incrementFollowings(long userId, int delta) {
        }

        @Override
        public void incrementFollowers(long userId, int delta) {
        }

        @Override
        public void incrementPosts(long userId, int delta) {
        }

        @Override
        public void incrementLikesReceived(long userId, int delta) {
        }

        @Override
        public void incrementFavsReceived(long userId, int delta) {
        }

        @Override
        public void rebuildAllCounters(long userId) {
        }
    }

    private static final class NoOpResilienceGuard implements ResilienceGuard {
        @Override
        public <T> GuardResult<T> execute(String resourceName,
                                          com.tongji.common.resilience.GuardedOperation<T> operation,
                                          Supplier<T> fallbackSupplier,
                                          Predicate<Throwable> systemFailureClassifier) {
            try {
                return GuardResult.success(operation.execute());
            } catch (Exception exception) {
                return GuardResult.fallback(fallbackSupplier.get(), exception);
            }
        }
    }

    private final class StubPublishAttemptService extends PublishAttemptService {
        private PublishAcceptance acceptResult;
        private PublishStatusResponse statusResult;
        private int completedCalls;
        private Long failedAttemptId;
        private String failedStep;
        private String failedMessage;

        private StubPublishAttemptService() {
            super(
                    knowPostMapper,
                    org.mockito.Mockito.mock(PublishAttemptMapper.class),
                    new PublishValidationHelper(knowPostMapper),
                    org.mockito.Mockito.mock(com.tongji.common.id.IdService.class),
                    new NoOpContentPublishedPublisher(),
                    org.mockito.Mockito.mock(ResilienceGuard.class),
                    Clock.systemUTC()
            );
        }

        @Override
        public PublishAcceptance acceptPublish(long authorId, long postId, String idempotentKey) {
            return acceptResult;
        }

        @Override
        public PublishAttempt retryPublish(long authorId, long postId, long attemptId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
            return statusResult;
        }

        @Override
        public int recoverStuckPublishingAttempts() {
            return 0;
        }

        @Override
        public void completePublish(long authorId, long postId, long attemptId) {
            completedCalls++;
            callLog.add("complete:" + attemptId);
        }

        @Override
        public void failPublish(long authorId, long postId, long attemptId, String failedStep, String errorMessage) {
            callLog.add("fail:" + attemptId);
            this.failedAttemptId = attemptId;
            this.failedStep = failedStep;
            this.failedMessage = errorMessage;
        }

        @Override
        public void recordDerivedFailureFallback(long attemptId,
                                                 String taskType,
                                                 String targetType,
                                                 long targetId,
                                                 String failureReason,
                                                 Instant nextRetryHint) {
        }
    }
}
