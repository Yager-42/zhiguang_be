package com.tongji.knowpost.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.outbox.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishOutboxWriterTest {

    private OutboxMapper outboxMapper;
    private IdService idService;
    private PublishOutboxWriter writer;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(OutboxMapper.class);
        idService = mock(IdService.class);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(901L, 902L);
        writer = new PublishOutboxWriter(
                outboxMapper,
                idService,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void requestedEventUsesAttemptAndRunAsUniqueBusinessKey() {
        PublishAttempt attempt = PublishAttempt.builder()
                .attemptId(88L)
                .postId(9L)
                .creatorId(7L)
                .runVersion(2)
                .contentObjectKeySnapshot("posts/9/body.md")
                .contentSha256Snapshot("a".repeat(64))
                .updatedAt(Instant.parse("2026-08-28T10:15:30Z"))
                .build();

        writer.writeRequested(attempt);

        verify(outboxMapper).insertUnique(
                eq(901L),
                eq("publish-requested:88:2"),
                eq("knowpost"),
                eq(9L),
                eq("publish_requested"),
                contains("\"runVersion\":2")
        );
    }

    @Test
    void publishedEventUsesSameVersionFenceInUniqueBusinessKey() {
        writer.writeContentPublished(new ContentPublishedEvent(
                9L,
                7L,
                88L,
                2,
                Instant.parse("2026-08-28T10:16:00Z")
        ));

        verify(outboxMapper).insertUnique(
                eq(901L),
                eq("content-published:88:2"),
                eq("knowpost"),
                eq(9L),
                eq("content_published"),
                contains("\"runVersion\":2")
        );
    }
}
