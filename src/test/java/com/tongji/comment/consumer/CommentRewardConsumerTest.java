package com.tongji.comment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.event.CommentEventReader;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.wallet.service.ContentRewardService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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
        CommentRewardConsumer consumer = new CommentRewardConsumer(
                new CommentEventReader(new ObjectMapper().findAndRegisterModules()),
                rewardService, metrics);

        consumer.onMessage(json(CommentEventType.COMMENT_CREATED));

        verify(rewardService).rewardCommentCreation(7L, 101L);
        verify(metrics).sideEffect("reward", "success");
    }

    @Test
    void nonCreatedEventDoesNotReward() throws Exception {
        ContentRewardService rewardService = mock(ContentRewardService.class);
        CommentRewardConsumer consumer = new CommentRewardConsumer(
                new CommentEventReader(new ObjectMapper().findAndRegisterModules()),
                rewardService, mock(CommentMetrics.class));

        consumer.onMessage(json(CommentEventType.COMMENT_MODERATED));

        verify(rewardService, never()).rewardCommentCreation(7L, 101L);
    }

    private String json(CommentEventType type) throws Exception {
        CommentOutboxEvent event = new CommentOutboxEvent(201L, type, 101L, 9L, 0L, 0L, 7L,
                "client-1", null, LocalDateTime.of(2026, 8, 7, 10, 0));
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
    }
}
