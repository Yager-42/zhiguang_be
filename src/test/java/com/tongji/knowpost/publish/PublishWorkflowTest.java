package com.tongji.knowpost.publish;

import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.text.TextDigestConflictException;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishWorkflowTest {

    private static final byte[] BODY = "正文 body".getBytes(StandardCharsets.UTF_8);

    private PublishAttemptService publishAttemptService;
    private MinioStorageService minioStorageService;
    private TextStorageService textStorageService;
    private PublishWorkflow workflow;

    @BeforeEach
    void setUp() {
        publishAttemptService = mock(PublishAttemptService.class);
        minioStorageService = mock(MinioStorageService.class);
        textStorageService = mock(TextStorageService.class);
        workflow = new PublishWorkflow(publishAttemptService, minioStorageService, textStorageService);
    }

    @Test
    void validSnapshotArchivesTextBeforeCompletingMysqlState() throws Exception {
        PublishRequestedEvent event = event(sha256(BODY));
        when(publishAttemptService.shouldProcess(event)).thenReturn(true);
        when(minioStorageService.readObjectBytes("posts/9/body.md")).thenReturn(BODY);

        workflow.execute(event);

        InOrder order = inOrder(textStorageService, publishAttemptService);
        order.verify(textStorageService).savePostTextIdempotent(9L, "正文 body", sha256(BODY));
        order.verify(publishAttemptService).completePublish(event);
    }

    @Test
    void oldRunReplaySkipsObjectStorageAndArchive() {
        PublishRequestedEvent event = event(sha256(BODY));
        when(publishAttemptService.shouldProcess(event)).thenReturn(false);

        workflow.execute(event);

        verify(minioStorageService, never()).readObjectBytes("posts/9/body.md");
        verify(textStorageService, never()).savePostTextIdempotent(9L, "正文 body", sha256(BODY));
    }

    @Test
    void digestMismatchIsPermanentAndNeverArchivesContent() {
        PublishRequestedEvent event = event("f".repeat(64));
        when(publishAttemptService.shouldProcess(event)).thenReturn(true);
        when(minioStorageService.readObjectBytes("posts/9/body.md")).thenReturn(BODY);

        assertThatThrownBy(() -> workflow.execute(event))
                .isInstanceOf(PermanentPublishException.class)
                .hasMessageContaining("正文摘要不匹配");

        verify(textStorageService, never()).savePostTextIdempotent(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(publishAttemptService, never()).completePublish(event);
    }

    @Test
    void conflictingExistingArchiveIsPermanent() {
        PublishRequestedEvent event = event(sha256(BODY));
        when(publishAttemptService.shouldProcess(event)).thenReturn(true);
        when(minioStorageService.readObjectBytes("posts/9/body.md")).thenReturn(BODY);
        org.mockito.Mockito.doThrow(new TextDigestConflictException("digest conflict"))
                .when(textStorageService).savePostTextIdempotent(9L, "正文 body", sha256(BODY));

        assertThatThrownBy(() -> workflow.execute(event))
                .isInstanceOf(PermanentPublishException.class)
                .hasMessageContaining("digest conflict");
        verify(publishAttemptService, never()).completePublish(event);
    }

    private PublishRequestedEvent event(String sha256) {
        return new PublishRequestedEvent(
                88L,
                9L,
                7L,
                1,
                "posts/9/body.md",
                "etag-1",
                sha256,
                Instant.parse("2026-08-28T10:15:30Z")
        );
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
