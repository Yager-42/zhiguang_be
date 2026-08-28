package com.tongji.knowpost.manager;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedEvent;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.knowpost.publish.PublishOutboxWriter;
import com.tongji.knowpost.publish.PublishRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishManagerAttemptServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:15:30Z");
    private static final String SHA256 = "a".repeat(64);

    private KnowPostMapper knowPostMapper;
    private PublishAttemptMapper publishAttemptMapper;
    private PublishOutboxWriter outboxWriter;
    private IdService idService;
    private PublishAttemptService service;

    @BeforeEach
    void setUp() {
        knowPostMapper = mock(KnowPostMapper.class);
        publishAttemptMapper = mock(PublishAttemptMapper.class);
        outboxWriter = mock(PublishOutboxWriter.class);
        idService = mock(IdService.class);
        service = new PublishAttemptService(
                knowPostMapper,
                publishAttemptMapper,
                new PublishValidationHelper(knowPostMapper),
                idService,
                outboxWriter,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void acceptPublishPersistsFrozenSnapshotAndRequestedOutbox() {
        KnowPost draft = post("draft", null);
        draft.setContentObjectKey("posts/9/body.md");
        draft.setContentEtag("etag-1");
        draft.setContentSha256(SHA256.toUpperCase());
        when(knowPostMapper.findById(9L)).thenReturn(draft);
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(88L);
        when(knowPostMapper.startPublishing(9L, 7L, 88L)).thenReturn(1);

        PublishAttempt accepted = service.acceptPublish(7L, 9L, "publish-key");

        assertThat(accepted.getAttemptId()).isEqualTo(88L);
        assertThat(accepted.getRunVersion()).isEqualTo(1);
        assertThat(accepted.getContentObjectKeySnapshot()).isEqualTo("posts/9/body.md");
        assertThat(accepted.getContentEtagSnapshot()).isEqualTo("etag-1");
        assertThat(accepted.getContentSha256Snapshot()).isEqualTo(SHA256);
        assertThat(accepted.getUpdatedAt()).isEqualTo(NOW);
        verify(publishAttemptMapper).insert(accepted);
        verify(outboxWriter).writeRequested(accepted);
    }

    @Test
    void idempotencyReplayReturnsExistingAttemptWithoutWritingAnotherEvent() {
        PublishAttempt existing = attempt("publishing", 1);
        when(publishAttemptMapper.findByIdempotencyKey(7L, 9L, "same-key")).thenReturn(existing);

        PublishAttempt replay = service.acceptPublish(7L, 9L, "same-key");

        assertThat(replay).isSameAs(existing);
        verify(publishAttemptMapper, never()).insert(any());
        verify(outboxWriter, never()).writeRequested(any());
    }

    @Test
    void retryFailedAttemptAdvancesVersionAndPreservesSnapshot() {
        PublishAttempt failed = attempt("failed", 1);
        failed.setRetryCount(2);
        KnowPost post = post("publish_failed", 88L);
        when(publishAttemptMapper.findById(88L)).thenReturn(failed);
        when(knowPostMapper.findPublishStatus(9L, 7L)).thenReturn(post);
        when(publishAttemptMapper.restartFailedAttempt(88L, 1)).thenReturn(1);
        when(knowPostMapper.retryPublishing(9L, 7L, 88L)).thenReturn(1);

        PublishAttempt retried = service.retryPublish(7L, 9L, 88L);

        assertThat(retried.getRunVersion()).isEqualTo(2);
        assertThat(retried.getRetryCount()).isEqualTo(3);
        assertThat(retried.getStatus()).isEqualTo("publishing");
        assertThat(retried.getContentObjectKeySnapshot()).isEqualTo("posts/9/body.md");
        assertThat(retried.getContentSha256Snapshot()).isEqualTo(SHA256);
        verify(outboxWriter).writeRequested(retried);
    }

    @Test
    void onlySuccessfulAttemptCasCompletesPostAndWritesPublishedFact() {
        PublishAttempt current = attempt("publishing", 1);
        PublishRequestedEvent event = event(1);
        when(publishAttemptMapper.findById(88L)).thenReturn(current);
        when(publishAttemptMapper.markSucceeded(88L, 1, NOW)).thenReturn(1);
        when(knowPostMapper.completePublish(9L, 7L, 88L, NOW)).thenReturn(1);

        service.completePublish(event);

        ArgumentCaptor<ContentPublishedEvent> published = ArgumentCaptor.forClass(ContentPublishedEvent.class);
        verify(outboxWriter).writeContentPublished(published.capture());
        assertThat(published.getValue().runVersion()).isEqualTo(1);
        assertThat(published.getValue().publishedAt()).isEqualTo(NOW);
    }

    @Test
    void staleSuccessCannotCompletePostOrWritePublishedFact() {
        PublishRequestedEvent staleEvent = event(1);
        PublishAttempt current = attempt("publishing", 2);
        when(publishAttemptMapper.findById(88L)).thenReturn(current);
        when(publishAttemptMapper.markSucceeded(88L, 1, NOW)).thenReturn(0);
        when(publishAttemptMapper.findStatusById(88L)).thenReturn(current);

        service.completePublish(staleEvent);

        verify(knowPostMapper, never()).completePublish(any(Long.class), any(Long.class), any(Long.class), any());
        verify(outboxWriter, never()).writeContentPublished(any());
    }

    @Test
    void staleDltFailureCannotMoveCurrentRunToFailed() {
        PublishRequestedEvent staleEvent = event(1);
        PublishAttempt current = attempt("publishing", 2);
        when(publishAttemptMapper.findById(88L)).thenReturn(current);
        when(publishAttemptMapper.markFailed(88L, 1, "publish_dlt", "exhausted")).thenReturn(0);
        when(publishAttemptMapper.findStatusById(88L)).thenReturn(current);

        service.failPublish(staleEvent, "publish_dlt", "exhausted");

        verify(knowPostMapper, never()).failPublish(any(Long.class), any(Long.class), any(Long.class), any());
    }

    @Test
    void oldRunRequestIsSkippedBeforeExternalWork() {
        PublishAttempt current = attempt("publishing", 2);
        when(publishAttemptMapper.findById(88L)).thenReturn(current);

        assertThat(service.shouldProcess(event(1))).isFalse();
    }

    private PublishAttempt attempt(String status, int runVersion) {
        return PublishAttempt.builder()
                .attemptId(88L)
                .postId(9L)
                .creatorId(7L)
                .idempotentKey("publish-key")
                .status(status)
                .runVersion(runVersion)
                .contentObjectKeySnapshot("posts/9/body.md")
                .contentEtagSnapshot("etag-1")
                .contentSha256Snapshot(SHA256)
                .retryCount(0)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private KnowPost post(String status, Long attemptId) {
        return KnowPost.builder()
                .id(9L)
                .creatorId(7L)
                .status(status)
                .publishAttemptId(attemptId)
                .build();
    }

    private PublishRequestedEvent event(int runVersion) {
        return new PublishRequestedEvent(
                88L,
                9L,
                7L,
                runVersion,
                "posts/9/body.md",
                "etag-1",
                SHA256,
                NOW
        );
    }
}
