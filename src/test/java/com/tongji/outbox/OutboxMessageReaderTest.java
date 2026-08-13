package com.tongji.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMessageReaderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsCompleteOutboxEnvelopeAndTypedPayload() {
        String payloadJson = objectMapper.createObjectNode()
                .put("eventType", "content_published")
                .put("postId", 101L)
                .put("publishedAt", "2026-06-18T10:15:30Z")
                .toString();
        String message = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT")
                .set("data", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("id", "77")
                        .put("aggregate_type", "following")
                        .put("aggregate_id", "9")
                        .put("type", "FollowCreated")
                        .put("payload", payloadJson)
                        .put("created_at", "2026-06-18 10:15:30.000")))
                .toString();

        List<OutboxEvent> events = OutboxMessageReader.read(objectMapper, message);

        assertThat(events).containsExactly(new OutboxEvent(
                77L,
                "following",
                9L,
                "FollowCreated",
                "{\"eventType\":\"content_published\",\"postId\":101,\"publishedAt\":\"2026-06-18T10:15:30Z\"}",
                "2026-06-18 10:15:30.000"
        ));
        OutboxPayload payload = events.getFirst().parsePayload(objectMapper).orElseThrow();
        assertThat(payload.text("eventType")).isEqualTo("content_published");
        assertThat(payload.longValue("postId")).isEqualTo(101L);
        assertThat(payload.instantValue("publishedAt")).isEqualTo("2026-06-18T10:15:30Z");
    }

    @Test
    void rejectsMalformedAndUnrelatedCanalMessages() {
        assertThat(OutboxMessageReader.read(objectMapper, "not-json")).isEmpty();
        assertThat(OutboxMessageReader.read(objectMapper,
                "{\"table\":\"users\",\"type\":\"INSERT\",\"data\":[]}"))
                .isEmpty();
        assertThat(OutboxMessageReader.read(objectMapper,
                "{\"table\":\"outbox\",\"type\":\"DELETE\",\"data\":[]}"))
                .isEmpty();
    }
}
