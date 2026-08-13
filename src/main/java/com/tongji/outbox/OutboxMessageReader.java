package com.tongji.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Canal JSON messages into typed outbox envelopes.
 */
@Component
public class OutboxMessageReader {
    private final ObjectMapper objectMapper;

    public OutboxMessageReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<OutboxEvent> read(String message) {
        return read(objectMapper, message);
    }

    public static List<OutboxEvent> read(ObjectMapper objectMapper, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (!"outbox".equals(text(root.get("table")))) {
                return List.of();
            }
            String changeType = text(root.get("type"));
            if (!"INSERT".equals(changeType) && !"UPDATE".equals(changeType)) {
                return List.of();
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return List.of();
            }

            List<OutboxEvent> events = new ArrayList<>();
            for (JsonNode row : data) {
                String payload = text(row.get("payload"));
                if (payload == null) {
                    continue;
                }
                events.add(new OutboxEvent(
                        longValue(row.get("id")),
                        text(row.get("aggregate_type")),
                        longValue(row.get("aggregate_id")),
                        text(row.get("type")),
                        payload,
                        text(row.get("created_at"))
                ));
            }
            return List.copyOf(events);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Long longValue(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
