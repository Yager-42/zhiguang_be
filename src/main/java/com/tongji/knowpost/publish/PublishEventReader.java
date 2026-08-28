package com.tongji.knowpost.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 严格解析共享 Canal Outbox envelope 中的发布事件。
 *
 * <p>非发布事件被忽略；目标发布行缺字段或格式错误时抛出异常，防止关键消息被静默确认。</p>
 *
 * @since 2026-08-28
 */
@Component
public class PublishEventReader {

    private final ObjectMapper objectMapper;

    public PublishEventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取发布请求事件。
     *
     * @param message Canal envelope JSON
     * @return 当前 envelope 内的发布请求；非 Outbox 或无目标行时返回空列表
     * @throws IllegalArgumentException 当 envelope 或目标发布行格式非法时
     */
    public List<PublishRequestedEvent> readRequested(String message) {
        List<PublishRequestedEvent> events = new ArrayList<>();
        for (JsonNode row : rows(message)) {
            if (!PublishOutboxWriter.PUBLISH_REQUESTED.equals(text(row.get("type")))) {
                continue;
            }
            JsonNode payload = payload(row);
            requireEventType(payload, PublishOutboxWriter.PUBLISH_REQUESTED);
            events.add(new PublishRequestedEvent(
                    positiveLong(payload, "attemptId"),
                    positiveLong(payload, "postId"),
                    positiveLong(payload, "authorId"),
                    positiveInt(payload, "runVersion"),
                    requiredText(payload, "contentObjectKey"),
                    optionalText(payload, "contentEtag"),
                    sha256(payload, "contentSha256"),
                    instant(payload, "requestedAt")
            ));
        }
        return List.copyOf(events);
    }

    /**
     * 读取发布完成事件。
     *
     * @param message Canal envelope JSON
     * @return 当前 envelope 内的发布完成消息；非 Outbox 或无目标行时返回空列表
     * @throws IllegalArgumentException 当 envelope 或目标发布行格式非法时
     */
    public List<ContentPublishedMessage> readPublished(String message) {
        List<ContentPublishedMessage> events = new ArrayList<>();
        for (JsonNode row : rows(message)) {
            if (!PublishOutboxWriter.CONTENT_PUBLISHED.equals(text(row.get("type")))) {
                continue;
            }
            JsonNode payload = payload(row);
            requireEventType(payload, PublishOutboxWriter.CONTENT_PUBLISHED);
            events.add(new ContentPublishedMessage(
                    positiveLong(payload, "postId"),
                    positiveLong(payload, "authorId"),
                    positiveLong(payload, "publishAttemptId"),
                    positiveInt(payload, "runVersion"),
                    instant(payload, "publishedAt")
            ));
        }
        return List.copyOf(events);
    }

    private List<JsonNode> rows(String message) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid Canal Outbox envelope", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid Canal Outbox envelope");
        }
        if (!"outbox".equals(text(root.get("table")))) {
            return List.of();
        }
        String changeType = text(root.get("type"));
        if (!"INSERT".equals(changeType) && !"UPDATE".equals(changeType)) {
            return List.of();
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalArgumentException("Outbox envelope data must be an array");
        }
        List<JsonNode> rows = new ArrayList<>(data.size());
        for (JsonNode row : data) {
            if (row == null || !row.isObject()) {
                throw new IllegalArgumentException("Outbox row must be an object");
            }
            rows.add(row);
        }
        return rows;
    }

    private JsonNode payload(JsonNode row) {
        String rawPayload = requiredText(row, "payload");
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("publish payload must be an object");
            }
            return payload;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid publish payload", exception);
        }
    }

    private void requireEventType(JsonNode payload, String expected) {
        if (!expected.equals(requiredText(payload, "eventType"))) {
            throw new IllegalArgumentException("publish payload eventType mismatch");
        }
    }

    private long positiveLong(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        try {
            long value = node != null && node.isIntegralNumber()
                    ? node.longValue()
                    : Long.parseLong(text(node));
            if (value <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer", exception);
        }
    }

    private int positiveInt(JsonNode payload, String field) {
        long value = positiveLong(payload, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " exceeds integer range");
        }
        return (int) value;
    }

    private String requiredText(JsonNode payload, String field) {
        String value = text(payload.get(field));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String optionalText(JsonNode payload, String field) {
        String value = text(payload.get(field));
        return value == null || value.isBlank() ? null : value;
    }

    private String sha256(JsonNode payload, String field) {
        String value = requiredText(payload, field);
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private Instant instant(JsonNode payload, String field) {
        try {
            return Instant.parse(requiredText(payload, field));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
