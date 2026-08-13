package com.tongji.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Parsed outbox payload with shared scalar conversion rules.
 */
public final class OutboxPayload {
    private final ObjectMapper objectMapper;
    private final JsonNode node;

    OutboxPayload(ObjectMapper objectMapper, JsonNode node) {
        this.objectMapper = objectMapper;
        this.node = node;
    }

    public String text(String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public Long longValue(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public Instant instantValue(String field) {
        String value = text(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public <T> T as(Class<T> type) {
        return objectMapper.convertValue(node, type);
    }

    public <T> T fieldAs(String field, Class<T> type) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : objectMapper.convertValue(value, type);
    }
}
