package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.GuardedOperation;
import com.tongji.common.resilience.ResilienceGuard;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishManagerImplTest {

    @Mock
    private UserCounterService userCounterService;
    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private PublishAttemptMapper publishAttemptMapper;
    @Mock
    private TextStorageService textStorageService;

    private CapturingTaskExecutor capturingTaskExecutor;
    private CapturingTaskExecutor reconciliationTaskExecutor;
    private StubPublishAttemptService publishAttemptService;
    private RecordingContentPublishedPublisher contentPublishedPublisher;
    private RecordingResilienceGuard resilienceGuard;
    private StubMinioStorageService minioStorageService;
    private StubRestTemplate restTemplate;
    private PublishManager manager;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        capturingTaskExecutor = new CapturingTaskExecutor();
        reconciliationTaskExecutor = new CapturingTaskExecutor();
        publishAttemptService = new StubPublishAttemptService();
        contentPublishedPublisher = new RecordingContentPublishedPublisher();
        resilienceGuard = new RecordingResilienceGuard();
        minioStorageService = new StubMinioStorageService();
        restTemplate = new StubRestTemplate();
        when(knowPostMapper.findById(9L)).thenReturn(KnowPost.builder()
                .id(9L)
                .creatorId(7L)
                .contentUrl("https://cdn.example.com/posts/9.md")
                .contentSha256("sha-9")
                .status("publishing")
                .build());
        restTemplate.responseBody = "post-body";
        manager = newManager(capturingTaskExecutor, reconciliationTaskExecutor);
    }

    @Test
    void acceptPublishRejectsMissingIdempotentKey() {
        assertThatThrownBy(() -> manager.acceptPublish(7L, 9L, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        assertThat(publishAttemptService.acceptResult).isNull();
        assertThat(contentPublishedPublisher.derivedFailures).isEmpty();
        verifyNoInteractions(userCounterService);
        verifyNoInteractions(textStorageService);
    }

    @Test
    void acceptPublishReturnsAcceptedResponseBeforeFinalPublication() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);

        PublishAcceptedResponse response = manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        assertThat(capturingTaskExecutor.tasks).hasSize(1);
        assertThat(publishAttemptService.completedCalls).isZero();
        verifyNoInteractions(textStorageService);
    }

    @Test
    void acceptPublishDoesNotRescheduleDuplicateIdempotentAttempt() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), false);

        PublishAcceptedResponse response = manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        assertThat(capturingTaskExecutor.tasks).isEmpty();
        assertThat(publishAttemptService.completedCalls).isZero();
        verifyNoInteractions(textStorageService);
    }

    @Test
    void publishManagerDoesNotExposePublicSixArgumentBypassConstructor() {
        assertThat(java.util.Arrays.stream(PublishManagerImpl.class.getConstructors()))
                .noneMatch(constructor -> constructor.getParameterCount() == 6);
    }

    @Test
    void acceptPublishMarksAttemptFailedWhenCriticalWorkThrows() {
        PublishManager directManager = newManager(Runnable::run, Runnable::run);
        RuntimeException failure = new RuntimeException("critical failure");
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        publishAttemptService.completeFailure = failure;

        directManager.acceptPublish(7L, 9L, "publish-key");

        assertThat(publishAttemptService.failedAttemptId).isEqualTo(88L);
        assertThat(publishAttemptService.failedStep).isEqualTo("critical_publish");
        assertThat(contentPublishedPublisher.derivedFailures).isEmpty();
    }

    @Test
    void acceptPublishRecordsDerivedFailureWithoutRollingBackPublishedState() {
        PublishManager directManager = newManager(Runnable::run, Runnable::run);
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        doThrow(new RuntimeException("counter unavailable")).when(userCounterService).incrementPosts(7L, 1);

        directManager.acceptPublish(7L, 9L, "publish-key");

        assertThat(publishAttemptService.completedCalls).isEqualTo(1);
        assertThat(resilienceGuard.resourceNames).contains("publish:user-counter");
        assertThat(contentPublishedPublisher.derivedFailures).singleElement().satisfies(failure -> {
            assertThat(failure.taskType).isEqualTo("user_counter_increment");
            assertThat(failure.targetType).isEqualTo("knowpost");
            assertThat(failure.targetId).isEqualTo(9L);
            assertThat(failure.failureReason).isEqualTo("counter unavailable");
        });
        assertThat(publishAttemptService.failedAttemptId).isNull();
    }

    @Test
    void acceptPublishRunsDerivedCounterWorkOnReconciliationExecutor() {
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);

        manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(capturingTaskExecutor.tasks).hasSize(1);
        capturingTaskExecutor.tasks.get(0).run();

        assertThat(publishAttemptService.completedCalls).isEqualTo(1);
        assertThat(reconciliationTaskExecutor.tasks).hasSize(1);
        verifyNoInteractions(userCounterService);

        reconciliationTaskExecutor.tasks.get(0).run();

        verify(userCounterService).incrementPosts(7L, 1);
    }

    @Test
    void retryPublishReusesSameAttemptAndSchedulesWork() {
        publishAttemptService.retryResult = attempt(88L, 9L, 7L);

        PublishAcceptedResponse response = manager.retryPublish(7L, 9L, 88L);

        assertThat(response.publishAttemptId()).isEqualTo("88");
        assertThat(capturingTaskExecutor.tasks).hasSize(1);
        assertThat(publishAttemptService.completedCalls).isZero();
        verifyNoInteractions(textStorageService);
    }

    @Test
    void getPublishStatusDelegatesToAttemptService() {
        PublishStatusResponse expected = new PublishStatusResponse("88", "failed", "publish_failed", "critical_publish", true);
        publishAttemptService.statusResult = expected;

        PublishStatusResponse status = manager.getPublishStatus(7L, 9L, 88L);

        assertThat(status).isSameAs(expected);
        verifyNoInteractions(textStorageService);
    }

    @Test
    void recoverStuckPublishingDelegatesToAttemptService() {
        publishAttemptService.recoveredCount = 2;
        String callerThread = Thread.currentThread().getName();
        ThreadRecordingTaskExecutor recordingExecutor = new ThreadRecordingTaskExecutor("reconciliation-test");
        PublishManager threadedManager = newManager(capturingTaskExecutor, recordingExecutor);

        int recovered = ((PublishManagerImpl) threadedManager).recoverStuckPublishing();

        assertThat(recovered).isEqualTo(2);
        assertThat(publishAttemptService.recoverCalls).isEqualTo(1);
        assertThat(publishAttemptService.recoverThreadName).startsWith("reconciliation-test");
        assertThat(publishAttemptService.recoverThreadName).isNotEqualTo(callerThread);
        assertThat(recordingExecutor.executedTasks).isEqualTo(1);
        verifyNoInteractions(textStorageService);
    }

    @Test
    void acceptPublishPersistsFallbackWhenDerivedFailurePublicationAlsoFails() {
        PublishManager directManager = newManager(Runnable::run, Runnable::run);
        publishAttemptService.acceptResult = new PublishAcceptance(attempt(88L, 9L, 7L), true);
        doThrow(new RuntimeException("counter unavailable")).when(userCounterService).incrementPosts(7L, 1);
        contentPublishedPublisher.derivedFailureException = new RuntimeException("outbox unavailable");

        directManager.acceptPublish(7L, 9L, "publish-key");

        assertThat(publishAttemptService.completedCalls).isEqualTo(1);
        assertThat(publishAttemptService.derivedFailureFallbacks).singleElement().satisfies(fallback -> {
            assertThat(fallback.taskType).isEqualTo("user_counter_increment");
            assertThat(fallback.targetType).isEqualTo("knowpost");
            assertThat(fallback.targetId).isEqualTo(9L);
            assertThat(fallback.failureReason).contains("counter unavailable");
            assertThat(fallback.nextRetryHint).isNotNull();
        });
        assertThat(publishAttemptService.failedAttemptId).isNull();
    }

    private PublishManager newManager(TaskExecutor publishExecutor, TaskExecutor reconciliationExecutor) {
        return new PublishManagerImpl(
                publishAttemptService,
                contentPublishedPublisher,
                userCounterService,
                resilienceGuard,
                publishExecutor,
                reconciliationExecutor,
                textStorageService,
                knowPostMapper,
                minioStorageService,
                restTemplate
        );
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

    private static final class CapturingTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    private static final class ThreadRecordingTaskExecutor implements TaskExecutor {
        private final String threadName;
        private int executedTasks;

        private ThreadRecordingTaskExecutor(String threadName) {
            this.threadName = threadName;
        }

        @Override
        public void execute(Runnable task) {
            executedTasks++;
            Thread thread = new Thread(task, threadName + "-" + executedTasks);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class StubRestTemplate extends RestTemplate {
        private String responseBody;

        @Override
        public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) throws RestClientException {
            return responseType.cast(responseBody);
        }
    }

    private static final class StubMinioStorageService extends MinioStorageService {
        private StubMinioStorageService() {
            super(new StorageProperties());
        }
    }

    private static final class RecordingResilienceGuard implements ResilienceGuard {
        private final List<String> resourceNames = new ArrayList<>();

        @Override
        public <T> GuardResult<T> execute(String resourceName,
                                          GuardedOperation<T> operation,
                                          Supplier<T> fallbackSupplier,
                                          Predicate<Throwable> systemFailureClassifier) {
            resourceNames.add(resourceName);
            try {
                return GuardResult.success(operation.execute());
            } catch (Exception exception) {
                return GuardResult.fallback(fallbackSupplier.get(), exception);
            }
        }
    }

    private final class StubPublishAttemptService extends PublishAttemptService {
        private PublishAcceptance acceptResult;
        private PublishAttempt retryResult;
        private PublishStatusResponse statusResult;
        private RuntimeException completeFailure;
        private int completedCalls;
        private Long failedAttemptId;
        private String failedStep;
        private int recoverCalls;
        private int recoveredCount;
        private String recoverThreadName;
        private final List<DerivedFailureFallbackRecord> derivedFailureFallbacks = new ArrayList<>();

        private StubPublishAttemptService() {
            super(
                    knowPostMapper,
                    publishAttemptMapper,
                    new PublishValidationHelper(knowPostMapper),
                    org.mockito.Mockito.mock(com.tongji.common.id.IdService.class),
                    contentPublishedPublisher,
                    org.mockito.Mockito.mock(com.tongji.common.resilience.ResilienceGuard.class),
                    Clock.systemUTC(),
                    org.mockito.Mockito.mock(com.tongji.wallet.service.ContentRewardService.class)
            );
        }

        @Override
        public PublishAcceptance acceptPublish(long authorId, long postId, String idempotentKey) {
            return acceptResult;
        }

        @Override
        public PublishAttempt retryPublish(long authorId, long postId, long attemptId) {
            return retryResult;
        }

        @Override
        public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
            return statusResult;
        }

        @Override
        public int recoverStuckPublishingAttempts() {
            recoverCalls++;
            recoverThreadName = Thread.currentThread().getName();
            return recoveredCount;
        }

        @Override
        public void completePublish(long authorId, long postId, long attemptId) {
            completedCalls++;
            if (completeFailure != null) {
                throw completeFailure;
            }
        }

        @Override
        public void failPublish(long authorId, long postId, long attemptId, String failedStep, String errorMessage) {
            this.failedAttemptId = attemptId;
            this.failedStep = failedStep;
        }

        @Override
        public void recordDerivedFailureFallback(long attemptId,
                                                 String taskType,
                                                 String targetType,
                                                 long targetId,
                                                 String failureReason,
                                                 Instant nextRetryHint) {
            derivedFailureFallbacks.add(new DerivedFailureFallbackRecord(
                    attemptId,
                    taskType,
                    targetType,
                    targetId,
                    failureReason,
                    nextRetryHint
            ));
        }
    }

    private static final class RecordingContentPublishedPublisher extends ContentPublishedPublisher {
        private final List<DerivedFailureRecord> derivedFailures = new ArrayList<>();
        private RuntimeException derivedFailureException;

        private RecordingContentPublishedPublisher() {
            super(null, null, null);
        }

        @Override
        public void publishDerivedFailure(String taskType, String targetType, Long targetId, String failureReason, Instant nextRetryHint) {
            if (derivedFailureException != null) {
                throw derivedFailureException;
            }
            derivedFailures.add(new DerivedFailureRecord(taskType, targetType, targetId, failureReason, nextRetryHint));
        }
    }

    private record DerivedFailureRecord(
            String taskType,
            String targetType,
            Long targetId,
            String failureReason,
            Instant nextRetryHint
    ) {}

    private record DerivedFailureFallbackRecord(
            Long attemptId,
            String taskType,
            String targetType,
            Long targetId,
            String failureReason,
            Instant nextRetryHint
    ) {}
}
