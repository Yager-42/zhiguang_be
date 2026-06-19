package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.outbox.OutboxTopics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ContentPublishedRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final TaskExecutor taskExecutor;
    private final ReconciliationService reconciliationService;

    public ContentPublishedRecommendationConsumer(ObjectMapper objectMapper,
                                                  GorseClient gorseClient,
                                                  GorseProperties properties,
                                                  @Qualifier("taskExecutor") TaskExecutor taskExecutor,
                                                  ReconciliationService reconciliationService) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.reconciliationService = reconciliationService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-content-published-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        List<PublishedItem> items = parse(message);
        if (items.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        taskExecutor.execute(() -> {
            for (PublishedItem item : items) {
                try {
                    gorseClient.upsertItem(item.postId(), item.authorId(), item.publishedAt());
                } catch (RuntimeException ignored) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.GORSE_ITEM_UPSERT,
                            ReconciliationTargetType.POST,
                            item.postId()
                    );
                }
            }
            acknowledgment.acknowledge();
        });
    }

    private List<PublishedItem> parse(String message) {
        List<PublishedItem> items = new ArrayList<>();
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, message)) {
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }
            try {
                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                if (!"content_published".equals(text(payload.get("eventType")))) {
                    continue;
                }
                Long postId = longValue(payload.get("postId"));
                Long authorId = longValue(payload.get("authorId"));
                Instant publishedAt = instantValue(payload.get("publishedAt"));
                if (postId != null && authorId != null && publishedAt != null) {
                    items.add(new PublishedItem(postId, authorId, publishedAt));
                }
            } catch (Exception ignored) {
            }
        }
        return items;
    }

    private String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private Long longValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return Long.parseLong(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant instantValue(JsonNode node) {
        if (node == null || node.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record PublishedItem(long postId, long authorId, Instant publishedAt) {
    }
}
