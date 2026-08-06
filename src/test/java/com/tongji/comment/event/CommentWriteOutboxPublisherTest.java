package com.tongji.comment.event;

import com.tongji.comment.config.CommentOutboxSchemaInitializer;
import com.tongji.comment.mapper.CommentWriteOutboxMapper;
import com.tongji.comment.model.CommentWriteOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentWriteOutboxPublisherTest {
    @Mock
    private CommentWriteOutboxMapper outboxMapper;
    @Mock
    private CommentWriteProducer producer;
    @Mock
    private CommentOutboxSchemaInitializer schemaInitializer;

    private CommentWriteOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CommentWriteOutboxPublisher(outboxMapper, producer, schemaInitializer, 100, 30);
    }

    @Test
    void publishReadyMarksClaimedRowsPublishedAfterKafkaAcknowledges() {
        CommentWriteOutbox outbox = outbox(0);
        when(outboxMapper.claimReady(anyString(), any(), any(), eq(100))).thenReturn(1);
        when(outboxMapper.findClaimed(anyString())).thenReturn(List.of(outbox));

        publisher.publishReady();

        verify(outboxMapper).releaseExpiredClaims(any());
        verify(producer).publish(new CommentWriteEvent(101L, 9L, 0L, 0L, 7L, "client-1", "hello"));
        verify(outboxMapper).markPublished(eq(101L), anyString(), any());
        verify(outboxMapper, never()).markRetry(any(), anyString(), anyInt(), any(), any());
    }

    @Test
    void publishReadyRequeuesClaimedRowsWithBackoffWhenKafkaFails() {
        CommentWriteOutbox outbox = outbox(2);
        when(outboxMapper.claimReady(anyString(), any(), any(), eq(100))).thenReturn(1);
        when(outboxMapper.findClaimed(anyString())).thenReturn(List.of(outbox));
        doThrow(new RuntimeException("kafka down")).when(producer).publish(any());
        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now();

        publisher.publishReady();

        verify(outboxMapper, never()).markPublished(any(), anyString(), any());
        verify(outboxMapper).markRetry(eq(101L), anyString(), eq(3), retryAt.capture(), eq("kafka down"));
        assertThat(retryAt.getValue()).isAfterOrEqualTo(before.plusSeconds(8));
    }

    @Test
    void publishReadyDoesNotQueryRowsWhenNothingCanBeClaimed() {
        when(outboxMapper.claimReady(anyString(), any(), any(), eq(100))).thenReturn(0);

        publisher.publishReady();

        verify(outboxMapper).releaseExpiredClaims(any());
        verify(outboxMapper, never()).findClaimed(anyString());
        verifyNoInteractions(producer);
    }

    private CommentWriteOutbox outbox(int attempts) {
        return CommentWriteOutbox.builder()
                .commentId(101L)
                .postId(9L)
                .rootId(0L)
                .parentId(0L)
                .creatorId(7L)
                .clientRequestId("client-1")
                .body("hello")
                .attemptCount(attempts)
                .build();
    }
}
