package com.tongji.comment.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 严格解析共享 Canal Outbox envelope 中的评论事件。
 *
 * <p>无关事件不解析 payload；目标评论行缺字段、语义冲突或格式错误时抛出异常，防止关键消息被静默确认。</p>
 *
 * @since 2026-08-28
 */
@Component
public class CommentCanalEventReader {
    private static final String OUTBOX_TABLE = "outbox";
    private static final String COMMENT_AGGREGATE = "comment";
    private static final Set<CommentEventType> MUTATION_TYPES = EnumSet.of(
            CommentEventType.COMMENT_CREATED,
            CommentEventType.COMMENT_DELETED,
            CommentEventType.COMMENT_MODERATED
    );

    private final ObjectMapper objectMapper;
    private final CommentEventReader eventReader;

    public CommentCanalEventReader(ObjectMapper objectMapper, CommentEventReader eventReader) {
        this.objectMapper = objectMapper;
        this.eventReader = eventReader;
    }

    /**
     * 读取评论正文物化请求。
     *
     * @param message Canal envelope JSON
     * @return 当前 envelope 内的评论写请求；无目标行时返回空列表
     * @throws IllegalArgumentException 当 envelope 或目标评论行格式非法时
     */
    public List<CommentOutboxEvent> readRequested(String message) {
        return readTargets(message, EnumSet.of(CommentEventType.COMMENT_WRITE_REQUESTED));
    }

    /**
     * 读取评论创建、删除和审核删除事实。
     *
     * @param message Canal envelope JSON
     * @return 当前 envelope 内的评论变更事件；无目标行时返回空列表
     * @throws IllegalArgumentException 当 envelope 或目标评论行格式非法时
     */
    public List<CommentOutboxEvent> readMutations(String message) {
        return readTargets(message, MUTATION_TYPES);
    }

    private List<CommentOutboxEvent> readTargets(String message, Set<CommentEventType> targetTypes) {
        List<CommentOutboxEvent> events = new ArrayList<>();
        for (JsonNode row : rows(message)) {
            CommentEventType rowType = targetType(row, targetTypes);
            if (rowType == null) {
                continue;
            }
            events.add(readTarget(row, rowType));
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
        if (!OUTBOX_TABLE.equals(text(root.get("table")))) {
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

    private CommentEventType targetType(JsonNode row, Set<CommentEventType> targetTypes) {
        String type = text(row.get("type"));
        for (CommentEventType targetType : targetTypes) {
            if (targetType.name().equals(type)) {
                return targetType;
            }
        }
        return null;
    }

    private CommentOutboxEvent readTarget(JsonNode row, CommentEventType rowType) {
        if (!COMMENT_AGGREGATE.equals(requiredText(row, "aggregate_type"))) {
            throw new IllegalArgumentException("comment Outbox row aggregate_type mismatch");
        }
        long rowId = positiveLong(row, "id");
        long aggregateId = positiveLong(row, "aggregate_id");
        CommentOutboxEvent event = eventReader.read(requiredText(row, "payload"));
        validateEvent(event, rowType, rowId, aggregateId);
        return event;
    }

    private void validateEvent(CommentOutboxEvent event,
                               CommentEventType rowType,
                               long rowId,
                               long aggregateId) {
        if (event == null || event.eventType() != rowType) {
            throw new IllegalArgumentException("comment payload eventType mismatch");
        }
        if (!Objects.equals(event.eventId(), rowId)) {
            throw new IllegalArgumentException("comment payload eventId mismatch");
        }
        if (!Objects.equals(event.commentId(), aggregateId)) {
            throw new IllegalArgumentException("comment payload commentId mismatch");
        }
        requirePositive(event.postId(), "postId");
        requirePositive(event.creatorId(), "creatorId");
        if (event.clientRequestId() == null || event.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        if (event.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        long rootId = nonNegative(event.rootId(), "rootId");
        long parentId = nonNegative(event.parentId(), "parentId");
        if ((rootId == 0L) != (parentId == 0L) || rootId != parentId) {
            throw new IllegalArgumentException("comment rootId and parentId mismatch");
        }
        if (rowType == CommentEventType.COMMENT_WRITE_REQUESTED
                && (event.body() == null || event.body().isBlank())) {
            throw new IllegalArgumentException("comment body is required");
        }
    }

    private long positiveLong(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        try {
            long value = valueNode != null && valueNode.isIntegralNumber()
                    ? valueNode.longValue()
                    : Long.parseLong(text(valueNode));
            if (value <= 0L) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer", exception);
        }
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private long nonNegative(Long value, String field) {
        if (value == null) {
            return 0L;
        }
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node.get(field));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
