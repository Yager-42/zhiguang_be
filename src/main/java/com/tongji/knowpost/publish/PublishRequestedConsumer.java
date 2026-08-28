package com.tongji.knowpost.publish;

import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * 消费持久化发布请求，并在 Listener 线程直接执行关键工作流。
 *
 * <p>临时故障使用有界指数退避；永久异常直接进入 DLT，由 DLT Handler 终止匹配版本。</p>
 *
 * @since 2026-08-28
 */
@Component
public class PublishRequestedConsumer {

    private static final String DLT_FAILURE_STEP = "publish_dlt";
    private static final String DLT_FAILURE_MESSAGE = "发布处理已耗尽 Kafka 重试或遇到永久错误";

    private final PublishEventReader eventReader;
    private final PublishWorkflow publishWorkflow;
    private final PublishAttemptService publishAttemptService;

    public PublishRequestedConsumer(PublishEventReader eventReader,
                                    PublishWorkflow publishWorkflow,
                                    PublishAttemptService publishAttemptService) {
        this.eventReader = eventReader;
        this.publishWorkflow = publishWorkflow;
        this.publishAttemptService = publishAttemptService;
    }

    /**
     * 处理一个 Canal Outbox envelope；旧版本或终态重放由工作流幂等跳过。
     *
     * @param message Canal Outbox envelope JSON
     */
    @RetryableTopic(
            attempts = "${publish.kafka.retry-attempts:10}",
            backoff = @Backoff(
                    delayExpression = "${publish.kafka.retry-delay-ms:1000}",
                    multiplierExpression = "${publish.kafka.retry-multiplier:2}",
                    maxDelayExpression = "${publish.kafka.retry-max-delay-ms:10000}"
            ),
            retryTopicSuffix = "-publish-retry",
            dltTopicSuffix = "-publish-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = {PermanentPublishException.class, IllegalArgumentException.class}
    )
    @KafkaListener(
            topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${publish.kafka.group:publish-requested-consumer}",
            containerFactory = "publishKafkaListenerContainerFactory"
    )
    public void onMessage(String message) {
        for (PublishRequestedEvent event : eventReader.readRequested(message)) {
            publishWorkflow.execute(event);
        }
    }

    /**
     * 将 DLT 中仍匹配当前 runVersion 的发布请求终止为失败。
     *
     * @param message 原始 Canal Outbox envelope JSON
     */
    @DltHandler
    public void onDlt(String message) {
        for (PublishRequestedEvent event : eventReader.readRequested(message)) {
            publishAttemptService.failPublish(event, DLT_FAILURE_STEP, DLT_FAILURE_MESSAGE);
        }
    }
}
