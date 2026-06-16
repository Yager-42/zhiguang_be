package com.tongji.knowpost.manager;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.GuardedOperation;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedEvent;
import com.tongji.knowpost.publish.ContentPublishedPublisher;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishManagerAttemptServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T10:00:00Z");

    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private PublishAttemptMapper publishAttemptMapper;
    @Mock
    private IdService idService;

    private PublishValidationHelper publishValidationHelper;
    private RecordingContentPublishedPublisher contentPublishedPublisher;
    private RecordingResilienceGuard resilienceGuard;
    private PublishAttemptService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        publishValidationHelper = new PublishValidationHelper(knowPostMapper);
        contentPublishedPublisher = new RecordingContentPublishedPublisher();
        resilienceGuard = new RecordingResilienceGuard();
        service = new PublishAttemptService(
                knowPostMapper,
                publishAttemptMapper,
                publishValidationHelper,
                idService,
                contentPublishedPublisher,
                resilienceGuard,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void acceptPublishReturnsExistingAttemptForSameIdempotentKey() {
        PublishAttempt existing = attempt(81L, 9L, 7L, "failed", 2, NOW.minusSeconds(30), "critical_publish", "boom");
        when(publishAttemptMapper.findByIdempotencyKey(7L, 9L, "same-key")).thenReturn(existing);

        PublishAcceptance accepted = service.acceptPublish(7L, 9L, "same-key");

        assertThat(accepted.attempt()).isSameAs(existing);
        assertThat(accepted.schedulePublishWork()).isFalse();
        verify(knowPostMapper, never()).findById(any());
        verify(publishAttemptMapper, never()).insert(any());
    }

    @Test
    void acceptPublishCreatesAttemptAndTransitionsDraftToPublishing() {
        KnowPost draft = post(9L, 7L, "draft", null, NOW.minusSeconds(5), null);
        when(publishAttemptMapper.findByIdempotencyKey(7L, 9L, "fresh-key")).thenReturn(null);
        when(knowPostMapper.findById(9L)).thenReturn(draft);
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(88L);
        when(knowPostMapper.startPublishing(9L, 7L, 88L)).thenReturn(1);

        PublishAcceptance accepted = service.acceptPublish(7L, 9L, "fresh-key");

        assertThat(accepted.schedulePublishWork()).isTrue();
        assertThat(accepted.attempt().getAttemptId()).isEqualTo(88L);
        assertThat(accepted.attempt().getStatus()).isEqualTo("publishing");
        assertThat(accepted.attempt().getRetryCount()).isZero();
        verify(knowPostMapper).startPublishing(9L, 7L, 88L);
        verify(publishAttemptMapper).insert(any(PublishAttempt.class));
    }

    @Test
    void acceptPublishRecoversOriginalAttemptWhenInsertHitsDuplicateKeyRace() {
        KnowPost draft = post(9L, 7L, "draft", null, NOW.minusSeconds(5), null);
        PublishAttempt existing = attempt(91L, 9L, 7L, "publishing", 0, NOW.minusSeconds(2), null, null);
        when(publishAttemptMapper.findByIdempotencyKey(7L, 9L, "race-key"))
                .thenReturn(null)
                .thenReturn(existing);
        when(knowPostMapper.findById(9L)).thenReturn(draft);
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(88L);
        doThrow(new DuplicateKeyException("duplicate")).when(publishAttemptMapper).insert(any(PublishAttempt.class));

        PublishAcceptance accepted = service.acceptPublish(7L, 9L, "race-key");

        assertThat(accepted.schedulePublishWork()).isFalse();
        assertThat(accepted.attempt()).isSameAs(existing);
        verify(knowPostMapper, never()).startPublishing(any(), any(), any());
    }

    @Test
    void retryPublishReusesOriginalAttemptRowAndIncrementsRetryCount() {
        PublishAttempt failedAttempt = attempt(88L, 9L, 7L, "failed", 1, NOW.minusSeconds(20), "critical_publish", "broken");
        KnowPost failedPost = post(9L, 7L, "publish_failed", 88L, NOW.minusSeconds(20), "broken");
        when(publishAttemptMapper.findById(88L)).thenReturn(failedAttempt);
        when(knowPostMapper.findPublishStatus(9L, 7L)).thenReturn(failedPost);
        when(knowPostMapper.retryPublishing(9L, 7L, 88L)).thenReturn(1);
        when(publishAttemptMapper.restartFailedAttempt(88L)).thenReturn(1);

        PublishAttempt retried = service.retryPublish(7L, 9L, 88L);

        assertThat(retried.getAttemptId()).isEqualTo(88L);
        assertThat(retried.getStatus()).isEqualTo("publishing");
        assertThat(retried.getRetryCount()).isEqualTo(2);
        assertThat(retried.getFailedStep()).isNull();
        assertThat(retried.getErrorMessage()).isNull();
        verify(knowPostMapper).retryPublishing(9L, 7L, 88L);
        verify(publishAttemptMapper).restartFailedAttempt(88L);
    }

    @Test
    void getPublishStatusRecoversStuckPublishingAttempt() {
        PublishAttempt stuckAttempt = attempt(88L, 9L, 7L, "publishing", 0, NOW.minusSeconds(360), null, null);
        KnowPost stuckPost = post(9L, 7L, "publishing", 88L, NOW.minusSeconds(360), null);
        when(publishAttemptMapper.findById(88L)).thenReturn(stuckAttempt);
        when(knowPostMapper.findPublishStatus(9L, 7L)).thenReturn(stuckPost);
        when(knowPostMapper.failPublish(9L, 7L, 88L, "Publishing timed out after 5 minutes")).thenReturn(1);
        when(publishAttemptMapper.markFailed(88L, "stuck_publishing", "Publishing timed out after 5 minutes")).thenReturn(1);

        PublishStatusResponse status = service.getPublishStatus(7L, 9L, 88L);

        assertThat(status.publishAttemptId()).isEqualTo("88");
        assertThat(status.attemptStatus()).isEqualTo("failed");
        assertThat(status.postStatus()).isEqualTo("publish_failed");
        assertThat(status.failedStep()).isEqualTo("stuck_publishing");
        assertThat(status.retryable()).isTrue();
    }

    @Test
    void completePublishWritesDurableEventAndMarksAttemptSucceeded() {
        PublishAttempt attempt = attempt(88L, 9L, 7L, "publishing", 0, NOW.minusSeconds(20), null, null);
        KnowPost publishingPost = post(9L, 7L, "publishing", 88L, NOW.minusSeconds(20), null);
        when(publishAttemptMapper.findById(88L)).thenReturn(attempt);
        when(knowPostMapper.findById(9L)).thenReturn(publishingPost);
        when(knowPostMapper.completePublish(9L, 7L, 88L)).thenReturn(1);
        when(publishAttemptMapper.markSucceeded(88L)).thenReturn(1);

        service.completePublish(7L, 9L, 88L);

        assertThat(resilienceGuard.resourceNames).contains("publish:content-published");
        assertThat(contentPublishedPublisher.publishedEvents).singleElement().satisfies(event -> {
            assertThat(event.postId()).isEqualTo(9L);
            assertThat(event.authorId()).isEqualTo(7L);
            assertThat(event.publishAttemptId()).isEqualTo(88L);
        });
        verify(knowPostMapper).completePublish(9L, 7L, 88L);
        verify(publishAttemptMapper).markSucceeded(88L);
    }

    @Test
    void completePublishStopsWhenDurableEventWriteFails() {
        PublishAttempt attempt = attempt(88L, 9L, 7L, "publishing", 0, NOW.minusSeconds(20), null, null);
        KnowPost publishingPost = post(9L, 7L, "publishing", 88L, NOW.minusSeconds(20), null);
        when(publishAttemptMapper.findById(88L)).thenReturn(attempt);
        when(knowPostMapper.findById(9L)).thenReturn(publishingPost);
        RuntimeException failure = new RuntimeException("outbox down");
        contentPublishedPublisher.publishFailure = failure;

        assertThatThrownBy(() -> service.completePublish(7L, 9L, 88L))
                .isSameAs(failure);

        verify(knowPostMapper, never()).completePublish(any(), any(), any());
        verify(publishAttemptMapper, never()).markSucceeded(any());
    }

    @Test
    void completePublishTreatsGuardFallbackAsCriticalFailure() {
        PublishAttempt attempt = attempt(88L, 9L, 7L, "publishing", 0, NOW.minusSeconds(20), null, null);
        KnowPost publishingPost = post(9L, 7L, "publishing", 88L, NOW.minusSeconds(20), null);
        when(publishAttemptMapper.findById(88L)).thenReturn(attempt);
        when(knowPostMapper.findById(9L)).thenReturn(publishingPost);
        resilienceGuard.forceFallback = true;

        assertThatThrownBy(() -> service.completePublish(7L, 9L, 88L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content published event degraded");

        verify(knowPostMapper, never()).completePublish(any(), any(), any());
        verify(publishAttemptMapper, never()).markSucceeded(any());
    }

    @Test
    void recoverStuckPublishingSweepsScheduledCandidates() {
        PublishAttempt stuckAttempt = attempt(88L, 9L, 7L, "publishing", 0, NOW.minusSeconds(360), null, null);
        KnowPost stuckPost = post(9L, 7L, "publishing", 88L, NOW.minusSeconds(360), null);
        when(publishAttemptMapper.findStuckPublishingAttempts(NOW.minusSeconds(300))).thenReturn(List.of(stuckAttempt));
        when(knowPostMapper.findPublishStatus(9L, 7L)).thenReturn(stuckPost);
        when(knowPostMapper.failPublish(9L, 7L, 88L, "Publishing timed out after 5 minutes")).thenReturn(1);
        when(publishAttemptMapper.markFailed(88L, "stuck_publishing", "Publishing timed out after 5 minutes")).thenReturn(1);

        int recovered = service.recoverStuckPublishingAttempts();

        assertThat(recovered).isEqualTo(1);
        assertThat(stuckAttempt.getStatus()).isEqualTo("failed");
        assertThat(stuckPost.getStatus()).isEqualTo("publish_failed");
        verify(publishAttemptMapper).findStuckPublishingAttempts(NOW.minusSeconds(300));
    }

    private static PublishAttempt attempt(Long attemptId,
                                          Long postId,
                                          Long creatorId,
                                          String status,
                                          int retryCount,
                                          Instant updatedAt,
                                          String failedStep,
                                          String errorMessage) {
        return PublishAttempt.builder()
                .attemptId(attemptId)
                .postId(postId)
                .creatorId(creatorId)
                .idempotentKey("key-" + attemptId)
                .status(status)
                .retryCount(retryCount)
                .failedStep(failedStep)
                .errorMessage(errorMessage)
                .createdAt(updatedAt.minusSeconds(10))
                .updatedAt(updatedAt)
                .build();
    }

    private static KnowPost post(Long postId,
                                 Long creatorId,
                                 String status,
                                 Long attemptId,
                                 Instant updateTime,
                                 String failedReason) {
        return KnowPost.builder()
                .id(postId)
                .creatorId(creatorId)
                .status(status)
                .publishAttemptId(attemptId)
                .publishFailedReason(failedReason)
                .updateTime(updateTime)
                .build();
    }

    private static final class RecordingContentPublishedPublisher extends ContentPublishedPublisher {
        private final List<ContentPublishedEvent> publishedEvents = new ArrayList<>();
        private RuntimeException publishFailure;

        private RecordingContentPublishedPublisher() {
            super(null, null, null);
        }

        @Override
        public void publish(ContentPublishedEvent event) {
            if (publishFailure != null) {
                throw publishFailure;
            }
            publishedEvents.add(event);
        }

        @Override
        public void publishDerivedFailure(String taskType, String targetType, Long targetId, String failureReason, Instant nextRetryHint) {
        }
    }

    private static final class RecordingResilienceGuard implements ResilienceGuard {
        private final List<String> resourceNames = new ArrayList<>();
        private boolean forceFallback;

        @Override
        public <T> GuardResult<T> execute(String resourceName,
                                          GuardedOperation<T> operation,
                                          Supplier<T> fallbackSupplier,
                                          Predicate<Throwable> systemFailureClassifier) {
            resourceNames.add(resourceName);
            if (forceFallback) {
                return GuardResult.fallback(fallbackSupplier.get(), null);
            }
            try {
                return GuardResult.success(operation.execute());
            } catch (Exception exception) {
                return GuardResult.fallback(fallbackSupplier.get(), exception);
            }
        }
    }
}
