package com.tongji.knowpost.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishEventReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PublishEventReader reader = new PublishEventReader(objectMapper);

    @Test
    void parsesVersionedPublishRequestedPayload() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("eventType", "publish_requested")
                .put("attemptId", 88L)
                .put("postId", 9L)
                .put("authorId", 7L)
                .put("runVersion", 2)
                .put("contentObjectKey", "posts/9/body.md")
                .put("contentEtag", "etag-1")
                .put("contentSha256", "A".repeat(64))
                .put("requestedAt", "2026-08-28T10:15:30Z");

        List<PublishRequestedEvent> events = reader.readRequested(envelope("publish_requested", payload));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.attemptId()).isEqualTo(88L);
            assertThat(event.runVersion()).isEqualTo(2);
            assertThat(event.contentSha256()).isEqualTo("a".repeat(64));
        });
    }

    @Test
    void rejectsTargetPublishRowWithInvalidDigest() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("eventType", "publish_requested")
                .put("attemptId", 88L)
                .put("postId", 9L)
                .put("authorId", 7L)
                .put("runVersion", 1)
                .put("contentObjectKey", "posts/9/body.md")
                .put("contentSha256", "invalid")
                .put("requestedAt", "2026-08-28T10:15:30Z");

        assertThatThrownBy(() -> reader.readRequested(envelope("publish_requested", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void ignoresNonPublishOutboxRowsWithoutParsingTheirPayload() throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT");
        ObjectNode row = objectMapper.createObjectNode()
                .put("type", "FollowCreated")
                .put("payload", "not-json");
        envelope.putArray("data").add(row);

        assertThat(reader.readRequested(objectMapper.writeValueAsString(envelope))).isEmpty();
        assertThat(reader.readPublished(objectMapper.writeValueAsString(envelope))).isEmpty();
    }

    private String envelope(String type, ObjectNode payload) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT");
        ObjectNode row = objectMapper.createObjectNode()
                .put("type", type)
                .put("payload", objectMapper.writeValueAsString(payload));
        envelope.putArray("data").add(row);
        return objectMapper.writeValueAsString(envelope);
    }
}
