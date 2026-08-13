package com.tongji.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * One row from the shared outbox table.
 */
public record OutboxEvent(
        Long id,
        String aggregateType,
        Long aggregateId,
        String type,
        String payload,
        String createdAt
) {
    public Optional<OutboxPayload> parsePayload(ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node == null || !node.isObject()
                    ? Optional.empty()
                    : Optional.of(new OutboxPayload(objectMapper, node));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public <T> Optional<T> payloadAs(ObjectMapper objectMapper, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
