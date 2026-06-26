package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.notification.service.NotificationCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CommentNotificationConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private KnowPostMapper knowPostMapper;
    private CommentMapper commentMapper;
    private NotificationCommandService commandService;
    private Acknowledgment acknowledgment;
    private CommentNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        knowPostMapper = mock(KnowPostMapper.class);
        commentMapper = mock(CommentMapper.class);
        commandService = mock(NotificationCommandService.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new CommentNotificationConsumer(objectMapper, knowPostMapper, commentMapper, commandService);
    }

    @Test
    void topLevelCommentNotifiesPostAuthor() throws Exception {
        KnowPost post = new KnowPost();
        post.setId(101L);
        post.setCreatorId(8L);
        when(knowPostMapper.findById(101L)).thenReturn(post);

        consumer.onMessage(objectMapper.writeValueAsString(
                new CommentFeedbackEvent(11L, 101L, 0L, 0L, 7L, CommentFeedbackEvent.COMMENT)), acknowledgment);

        verify(commandService).createCommentNotification(7L, 8L, 101L, 11L, "comment:create:11");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void replyOnlyNotifiesParentCommentAuthor() throws Exception {
        Comment parent = Comment.builder().commentId(12L).creatorId(9L).build();
        when(commentMapper.findById(12L)).thenReturn(parent);

        consumer.onMessage(objectMapper.writeValueAsString(
                new CommentFeedbackEvent(13L, 101L, 12L, 12L, 7L, CommentFeedbackEvent.COMMENT)), acknowledgment);

        verify(commandService).createCommentNotification(7L, 9L, 101L, 13L, "comment:create:13");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void deleteEventIsIgnored() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(
                new CommentFeedbackEvent(13L, 101L, 12L, 12L, 7L, CommentFeedbackEvent.DELETE)), acknowledgment);

        verify(acknowledgment).acknowledge();
        verifyNoMoreInteractions(commandService);
    }
}
