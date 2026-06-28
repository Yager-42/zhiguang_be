package com.tongji.moderation.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.moderation.service.ModerationReviewExecutor;
import com.tongji.relation.outbox.OutboxTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "moderation.llm", name = "enabled", havingValue = "true")
public class ModerationReviewConsumer {
    private final ObjectMapper objectMapper;
    private final ModerationReviewExecutor executor;

    @Autowired
    public ModerationReviewConsumer(ObjectMapper objectMapper,
                                    ModerationReviewExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "moderation-review-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
        for (JsonNode row : rows) {
            Long reportId = extractReportId(row);
            if (reportId != null) {
                try {
                    executor.review(reportId);
                } catch (RuntimeException exception) {
                    log.warn("moderation review failed, reportId={}: {}", reportId, exception.getMessage(), exception);
                    throw exception;
                }
            }
        }
        ack.acknowledge();
    }

    private Long extractReportId(JsonNode row) {
        JsonNode payloadNode = row.get("payload");
        if (payloadNode == null) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadNode.asText());
            if (!"moderation_report".equals(text(payload.get("entity")))
                    || !"review_requested".equals(text(payload.get("op")))) {
                return null;
            }
            Long reportId = asLong(payload.get("reportId"));
            if (reportId == null) {
                log.warn("moderation review outbox missing reportId");
            }
            return reportId;
        } catch (JsonProcessingException exception) {
            log.warn("moderation review outbox payload invalid: {}", exception.getMessage());
            return null;
        }
    }

    private String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private Long asLong(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
