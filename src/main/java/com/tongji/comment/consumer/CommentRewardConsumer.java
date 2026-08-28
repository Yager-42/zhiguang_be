package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentCanalEventReader;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.outbox.OutboxTopics;
import com.tongji.wallet.service.ContentRewardService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * 消费共享评论创建事实并通过钱包 businessRef 幂等奖励作者。
 *
 * @since 2026-08-28
 */
@Component
public class CommentRewardConsumer {
    private final CommentCanalEventReader eventReader;
    private final ContentRewardService contentRewardService;
    private final CommentMetrics metrics;

    public CommentRewardConsumer(CommentCanalEventReader eventReader,
                                 ContentRewardService contentRewardService,
                                 CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.contentRewardService = contentRewardService;
        this.metrics = metrics;
    }

    /**
     * 处理评论创建事实；钱包临时故障通过独立 Retry Topic 重试。
     *
     * @param message Canal Outbox envelope JSON
     */
    @RetryableTopic(
            attempts = "${comment.kafka.effect-retry-attempts:10}",
            backoff = @Backoff(
                    delayExpression = "${comment.kafka.effect-retry-delay-ms:1000}",
                    multiplierExpression = "${comment.kafka.effect-retry-multiplier:2}",
                    maxDelayExpression = "${comment.kafka.effect-retry-max-delay-ms:10000}"
            ),
            retryTopicSuffix = "-comment-reward-retry",
            dltTopicSuffix = "-comment-reward-dlt",
            exclude = IllegalArgumentException.class
    )
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX,
            groupId = "${comment.kafka.reward-group:comment-reward-effects}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        for (CommentOutboxEvent event : eventReader.readMutations(message)) {
            if (event.eventType() != CommentEventType.COMMENT_CREATED) {
                continue;
            }
            long rewarded = contentRewardService.rewardCommentCreation(event.creatorId(), event.commentId());
            metrics.sideEffect("reward", rewarded > 0 ? "success" : "disabled_or_zero");
        }
    }

}
