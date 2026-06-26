package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.notification.service.NotificationCommandService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class CommentNotificationConsumer {

    private final ObjectMapper objectMapper;
    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;
    private final NotificationCommandService notificationCommandService;

    public CommentNotificationConsumer(ObjectMapper objectMapper,
                                       KnowPostMapper knowPostMapper,
                                       CommentMapper commentMapper,
                                       NotificationCommandService notificationCommandService) {
        this.objectMapper = objectMapper;
        this.knowPostMapper = knowPostMapper;
        this.commentMapper = commentMapper;
        this.notificationCommandService = notificationCommandService;
    }

    @KafkaListener(
            topics = "${comment.kafka.feedback-topic:comment-feedback}",
            groupId = "notification-comment-consumer"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        CommentFeedbackEvent event = objectMapper.readValue(message, CommentFeedbackEvent.class);
        if (!CommentFeedbackEvent.COMMENT.equals(event.action())
                || event.commentId() == null
                || event.creatorId() == null
                || event.postId() == null) {
            acknowledgment.acknowledge();
            return;
        }
        Long recipientUserId = resolveRecipientUserId(event);
        if (recipientUserId != null) {
            notificationCommandService.createCommentNotification(
                    event.creatorId(),
                    recipientUserId,
                    event.postId(),
                    event.commentId(),
                    "comment:create:" + event.commentId()
            );
        }
        acknowledgment.acknowledge();
    }

    private Long resolveRecipientUserId(CommentFeedbackEvent event) {
        if (event.parentId() != null && event.parentId() > 0) {
            Comment parent = commentMapper.findById(event.parentId());
            return parent == null ? null : parent.getCreatorId();
        }
        KnowPost post = knowPostMapper.findById(event.postId());
        return post == null ? null : post.getCreatorId();
    }
}
