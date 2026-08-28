package com.tongji.comment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentCanalEventReaderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CommentCanalEventReader reader = new CommentCanalEventReader(
            objectMapper,
            new CommentEventReader(objectMapper)
    );

    @Test
    void readsRequestedEventWithMatchingOutboxIdentity() throws Exception {
        CommentOutboxEvent expected = event(CommentEventType.COMMENT_WRITE_REQUESTED, "hello");

        List<CommentOutboxEvent> events = reader.readRequested(envelope(expected));

        assertThat(events).containsExactly(expected);
    }

    @Test
    void readsMutationEventsWithoutTreatingRequestsAsMutations() throws Exception {
        CommentOutboxEvent created = event(CommentEventType.COMMENT_CREATED, null);
        CommentOutboxEvent requested = event(CommentEventType.COMMENT_WRITE_REQUESTED, "hello");

        assertThat(reader.readMutations(envelope(created))).containsExactly(created);
        assertThat(reader.readMutations(envelope(requested))).isEmpty();
    }

    @Test
    void ignoresUnrelatedOutboxRowsWithoutParsingPayload() throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT");
        envelope.putArray("data").add(objectMapper.createObjectNode()
                .put("type", "FollowCreated")
                .put("payload", "not-json"));

        assertThat(reader.readRequested(objectMapper.writeValueAsString(envelope))).isEmpty();
        assertThat(reader.readMutations(objectMapper.writeValueAsString(envelope))).isEmpty();
    }

    @Test
    void rejectsMatchingOutboxEnvelopeWithoutRows() {
        assertThatThrownBy(() -> reader.readRequested("{\"table\":\"outbox\",\"type\":\"INSERT\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outbox envelope data must be an array");
    }

    @Test
    void rejectsTargetRowWhenPayloadEventTypeDiffers() throws Exception {
        CommentOutboxEvent payload = event(CommentEventType.COMMENT_CREATED, null);

        assertThatThrownBy(() -> reader.readRequested(envelope(
                CommentEventType.COMMENT_WRITE_REQUESTED,
                payload,
                201L,
                101L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType mismatch");
    }

    @Test
    void rejectsRequestedEventWithoutBody() throws Exception {
        CommentOutboxEvent payload = event(CommentEventType.COMMENT_WRITE_REQUESTED, null);

        assertThatThrownBy(() -> reader.readRequested(envelope(payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("comment body is required");
    }

    @Test
    void rejectsOutboxIdentityMismatch() throws Exception {
        CommentOutboxEvent payload = event(CommentEventType.COMMENT_CREATED, null);

        assertThatThrownBy(() -> reader.readMutations(envelope(
                CommentEventType.COMMENT_CREATED,
                payload,
                202L,
                101L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("comment payload eventId mismatch");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        CommentOutboxEvent payload = event(
                CommentEventType.COMMENT_CREATED,
                null,
                CommentOutboxEvent.CURRENT_SCHEMA_VERSION + 1);

        assertThatThrownBy(() -> reader.readMutations(envelope(payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported comment payload schemaVersion");
    }

    @Test
    void rejectsMissingSchemaVersion() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(
                envelope(event(CommentEventType.COMMENT_CREATED, null)));
        ObjectNode row = (ObjectNode) root.get("data").get(0);
        ObjectNode payload = (ObjectNode) objectMapper.readTree(row.get("payload").asText());
        payload.remove("schemaVersion");
        row.put("payload", objectMapper.writeValueAsString(payload));

        assertThatThrownBy(() -> reader.readMutations(objectMapper.writeValueAsString(root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported comment payload schemaVersion");
    }

    private String envelope(CommentOutboxEvent event) throws Exception {
        return envelope(event.eventType(), event, event.eventId(), event.commentId());
    }

    private String envelope(CommentEventType rowType,
                            CommentOutboxEvent payload,
                            long rowId,
                            long aggregateId) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT");
        ObjectNode row = objectMapper.createObjectNode()
                .put("id", rowId)
                .put("aggregate_type", "comment")
                .put("aggregate_id", aggregateId)
                .put("type", rowType.name())
                .put("payload", objectMapper.writeValueAsString(payload));
        envelope.putArray("data").add(row);
        return objectMapper.writeValueAsString(envelope);
    }

    private CommentOutboxEvent event(CommentEventType type, String body) {
        return event(type, body, CommentOutboxEvent.CURRENT_SCHEMA_VERSION);
    }

    private CommentOutboxEvent event(CommentEventType type, String body, int schemaVersion) {
        return new CommentOutboxEvent(
                201L,
                type,
                schemaVersion,
                101L,
                9L,
                0L,
                0L,
                7L,
                "client-1",
                body,
                LocalDateTime.of(2026, 8, 7, 10, 0)
        );
    }
}
