package com.tongji.knowpost.publish;

import com.tongji.counter.service.UserCounterService;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * 独立消费发布事实并按 MySQL 已发布帖子事实重建作者计数。
 *
 * @since 2026-08-28
 */
@Component
public class PublishUserCounterConsumer {

    private final PublishEventReader eventReader;
    private final UserCounterService userCounterService;

    public PublishUserCounterConsumer(PublishEventReader eventReader, UserCounterService userCounterService) {
        this.eventReader = eventReader;
        this.userCounterService = userCounterService;
    }

    /**
     * 处理发布完成事件；重复投递只重复执行绝对值重建，不会重复加一。
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
            retryTopicSuffix = "-publish-counter-retry",
            dltTopicSuffix = "-publish-counter-dlt",
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(
            topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${publish.kafka.counter-group:publish-user-counter-consumer}",
            containerFactory = "publishKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        for (ContentPublishedMessage event : eventReader.readPublished(message)) {
            userCounterService.rebuildAllCounters(event.authorId());
        }
    }
}
