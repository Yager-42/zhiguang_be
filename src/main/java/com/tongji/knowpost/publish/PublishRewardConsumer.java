package com.tongji.knowpost.publish;

import com.tongji.outbox.OutboxTopics;
import com.tongji.wallet.service.ContentRewardService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * 独立消费发布事实并通过钱包 businessRef 幂等发放发帖奖励。
 *
 * @since 2026-08-28
 */
@Component
public class PublishRewardConsumer {

    private final PublishEventReader eventReader;
    private final ContentRewardService contentRewardService;

    public PublishRewardConsumer(PublishEventReader eventReader, ContentRewardService contentRewardService) {
        this.eventReader = eventReader;
        this.contentRewardService = contentRewardService;
    }

    /**
     * 处理发布完成事件；钱包临时故障通过独立 Retry Topic 重试。
     *
     * @param message Canal Outbox envelope JSON
     */
    @RetryableTopic(
            attempts = "${publish.kafka.effect-retry-attempts:10}",
            backoff = @Backoff(
                    delayExpression = "${publish.kafka.effect-retry-delay-ms:1000}",
                    multiplierExpression = "${publish.kafka.effect-retry-multiplier:2}",
                    maxDelayExpression = "${publish.kafka.effect-retry-max-delay-ms:10000}"
            ),
            retryTopicSuffix = "-publish-reward-retry",
            dltTopicSuffix = "-publish-reward-dlt",
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(
            topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${publish.kafka.reward-group:publish-reward-consumer}",
            containerFactory = "publishKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        for (ContentPublishedMessage event : eventReader.readPublished(message)) {
            contentRewardService.rewardPostCreationStrict(event.authorId(), event.postId());
        }
    }
}
