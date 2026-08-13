package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import org.springframework.stereotype.Component;

@Component
public class CommentEventReader {
    private final ObjectMapper objectMapper;

    public CommentEventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CommentOutboxEvent read(String message) {
        try {
            return objectMapper.readValue(message, CommentOutboxEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid comment event", exception);
        }
    }

    public CommentMutationEvent mutation(String message) {
        CommentOutboxEvent event = read(message);
        if (!isMutation(event.eventType())) {
            return null;
        }
        return new CommentMutationEvent(
                event.eventId(),
                event.eventType(),
                event.commentId(),
                event.postId(),
                value(event.rootId()),
                value(event.parentId())
        );
    }

    private boolean isMutation(CommentEventType eventType) {
        return eventType == CommentEventType.COMMENT_CREATED
                || eventType == CommentEventType.COMMENT_DELETED
                || eventType == CommentEventType.COMMENT_MODERATED;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
