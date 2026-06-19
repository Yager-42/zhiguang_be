package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.relation.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class ContentPublishedRagConsumer {

    private final ObjectMapper objectMapper;
    private final RagIndexService ragIndexService;
    private final ReconciliationService reconciliationService;

    public ContentPublishedRagConsumer(ObjectMapper objectMapper,
                                       RagIndexService ragIndexService,
                                       ReconciliationService reconciliationService) {
        this.objectMapper = objectMapper;
        this.ragIndexService = ragIndexService;
        this.reconciliationService = reconciliationService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-content-published-rag-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
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
                if (postId == null) {
                    continue;
                }
                try {
                    ragIndexService.ensureIndexedStrict(postId);
                } catch (RuntimeException ignored) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.RAG_INDEX,
                            ReconciliationTargetType.POST,
                            postId
                    );
                }
            } catch (Exception ignored) {
            }
        }
        acknowledgment.acknowledge();
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
}
