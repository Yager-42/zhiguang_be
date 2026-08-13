package com.tongji.comment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.cache.CommentMutationEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentEventReaderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CommentEventReader reader = new CommentEventReader(objectMapper);

    @Test
    void readsStableEnvelope() throws Exception {
        CommentOutboxEvent expected = event(CommentEventType.COMMENT_CREATED);

        CommentOutboxEvent actual = reader.read(objectMapper.writeValueAsString(expected));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsOnlyMutationEventsToLocalCacheSignal() throws Exception {
        CommentMutationEvent mutation = reader.mutation(objectMapper.writeValueAsString(
                event(CommentEventType.COMMENT_MODERATED)));
        CommentMutationEvent writeRequest = reader.mutation(objectMapper.writeValueAsString(
                event(CommentEventType.COMMENT_WRITE_REQUESTED)));

        assertThat(mutation).isEqualTo(new CommentMutationEvent(
                201L, CommentEventType.COMMENT_MODERATED, 101L, 9L, 0L, 0L));
        assertThat(writeRequest).isNull();
    }

    @Test
    void invalidEnvelopeHasOneStableFailureClassification() {
        assertThatThrownBy(() -> reader.read("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid comment event");
    }

    private CommentOutboxEvent event(CommentEventType type) {
        return new CommentOutboxEvent(201L, type, 101L, 9L, null, null, 7L,
                "client-1", null, LocalDateTime.of(2026, 8, 7, 10, 0));
    }
}
