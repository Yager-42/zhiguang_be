package com.tongji.comment.consumer;

import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.comment.event.CommentEventReader;
import com.tongji.wallet.service.ContentRewardService;
import com.tongji.comment.metrics.CommentMetrics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommentRewardConsumer {
    private final CommentEventReader eventReader;
    private final ContentRewardService contentRewardService;
    private final CommentMetrics metrics;

    public CommentRewardConsumer(CommentEventReader eventReader,
                                 ContentRewardService contentRewardService,
                                 CommentMetrics metrics) {
        this.eventReader = eventReader;
        this.contentRewardService = contentRewardService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.reward-group:comment-reward-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        CommentOutboxEvent event = eventReader.read(message);
        if (event.eventType() == CommentEventType.COMMENT_CREATED) {
            long rewarded = contentRewardService.rewardCommentCreation(event.creatorId(), event.commentId());
            metrics.sideEffect("reward", rewarded > 0 ? "success" : "disabled_or_zero");
        }
    }

}
