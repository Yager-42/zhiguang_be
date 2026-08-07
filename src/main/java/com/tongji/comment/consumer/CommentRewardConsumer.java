package com.tongji.comment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.tongji.wallet.service.ContentRewardService;
import com.tongji.comment.metrics.CommentMetrics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CommentRewardConsumer {
    private final ObjectMapper objectMapper;
    private final ContentRewardService contentRewardService;
    private final CommentMetrics metrics;

    public CommentRewardConsumer(ObjectMapper objectMapper,
                                 ContentRewardService contentRewardService,
                                 CommentMetrics metrics) {
        this.objectMapper = objectMapper;
        this.contentRewardService = contentRewardService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${comment.kafka.event-topic:comment-events}",
            groupId = "${comment.kafka.reward-group:comment-reward-consumer}",
            containerFactory = "commentEventKafkaListenerContainerFactory")
    public void onMessage(String message) {
        CommentOutboxEvent event = read(message);
        if (event.eventType() == CommentEventType.COMMENT_CREATED) {
            long rewarded = contentRewardService.rewardCommentCreation(event.creatorId(), event.commentId());
            metrics.sideEffect("reward", rewarded > 0 ? "success" : "disabled_or_zero");
        }
    }

    private CommentOutboxEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentOutboxEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment reward event", exception);
        }
    }
}
