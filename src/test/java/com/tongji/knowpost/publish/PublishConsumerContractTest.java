package com.tongji.knowpost.publish;

import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.wallet.service.ContentRewardService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishConsumerContractTest {

    @Test
    void criticalConsumerUsesBoundedRetryAndFailingDltHandler() throws Exception {
        RetryableTopic retry = PublishRequestedConsumer.class
                .getMethod("onMessage", String.class)
                .getAnnotation(RetryableTopic.class);

        assertThat(retry.attempts()).isEqualTo("${publish.kafka.retry-attempts:10}");
        assertThat(retry.exclude()).containsExactly(PermanentPublishException.class, IllegalArgumentException.class);
        assertThat(retry.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
    }

    @Test
    void dltFailsOnlyTheRunCarriedByTheMessage() {
        PublishEventReader reader = mock(PublishEventReader.class);
        PublishWorkflow workflow = mock(PublishWorkflow.class);
        PublishAttemptService attemptService = mock(PublishAttemptService.class);
        PublishRequestedEvent event = requestedEvent();
        when(reader.readRequested("message")).thenReturn(List.of(event));
        PublishRequestedConsumer consumer = new PublishRequestedConsumer(reader, workflow, attemptService);

        consumer.onDlt("message");

        verify(attemptService).failPublish(
                event,
                "publish_dlt",
                "发布处理已耗尽 Kafka 重试或遇到永久错误"
        );
    }

    @Test
    void rewardReplayDelegatesStablePostIdentityToWalletService() {
        PublishEventReader reader = mock(PublishEventReader.class);
        ContentRewardService rewardService = mock(ContentRewardService.class);
        ContentPublishedMessage event = publishedEvent();
        when(reader.readPublished("message")).thenReturn(List.of(event));
        PublishRewardConsumer consumer = new PublishRewardConsumer(reader, rewardService);

        consumer.onMessage("message");
        consumer.onMessage("message");

        verify(rewardService, times(2)).rewardPostCreationStrict(7L, 9L);
    }

    @Test
    void counterReplayRebuildsAbsoluteValueInsteadOfIncrementing() {
        PublishEventReader reader = mock(PublishEventReader.class);
        UserCounterService counterService = mock(UserCounterService.class);
        ContentPublishedMessage event = publishedEvent();
        when(reader.readPublished("message")).thenReturn(List.of(event));
        PublishUserCounterConsumer consumer = new PublishUserCounterConsumer(reader, counterService);

        consumer.onMessage("message");
        consumer.onMessage("message");

        verify(counterService, times(2)).rebuildAllCounters(7L);
        verify(counterService, times(0)).incrementPosts(7L, 1);
    }

    private PublishRequestedEvent requestedEvent() {
        return new PublishRequestedEvent(
                88L,
                9L,
                7L,
                2,
                "posts/9/body.md",
                "etag-1",
                "a".repeat(64),
                Instant.parse("2026-08-28T10:15:30Z")
        );
    }

    private ContentPublishedMessage publishedEvent() {
        return new ContentPublishedMessage(
                9L,
                7L,
                88L,
                2,
                Instant.parse("2026-08-28T10:16:00Z")
        );
    }
}
