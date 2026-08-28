package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.wallet.service.ContentRewardService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentRewardConsumerTest {

    @Test
    void createdEventRewardsByStableCommentBusinessKey() throws Exception {
        ContentRewardService rewardService = mock(ContentRewardService.class);
        CommentMetrics metrics = mock(CommentMetrics.class);
        when(rewardService.rewardCommentCreation(7L, 101L)).thenReturn(2L);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event(CommentEventType.COMMENT_CREATED);
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentRewardConsumer consumer = new CommentRewardConsumer(eventReader, rewardService, metrics);

        consumer.onMessage("message");

        verify(rewardService).rewardCommentCreation(7L, 101L);
        verify(metrics).sideEffect("reward", "success");
    }

    @Test
    void nonCreatedEventDoesNotReward() throws Exception {
        ContentRewardService rewardService = mock(ContentRewardService.class);
        CommentCanalEventReader eventReader = mock(CommentCanalEventReader.class);
        CommentOutboxEvent event = event(CommentEventType.COMMENT_MODERATED);
        when(eventReader.readMutations("message")).thenReturn(List.of(event));
        CommentRewardConsumer consumer = new CommentRewardConsumer(
                eventReader, rewardService, mock(CommentMetrics.class));

        consumer.onMessage("message");

        verify(rewardService, never()).rewardCommentCreation(7L, 101L);
    }

    private CommentOutboxEvent event(CommentEventType type) {
        return new CommentOutboxEvent(201L, type, 101L, 9L, 0L, 0L, 7L,
                "client-1", null, LocalDateTime.of(2026, 8, 7, 10, 0));
    }
}
