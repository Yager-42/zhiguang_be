package com.tongji.search.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxPayload;
import com.tongji.outbox.OutboxTopics;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.search.index.SearchIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索索引的 Outbox 消费者：监听 canal-outbox，驱动 ES 索引的增量更新。
 * 仅处理 entity=knowpost 的 upsert 与软删。
 */
@Service
@RequiredArgsConstructor
public class CanalOutboxConsumerSearch {
    private final ObjectMapper objectMapper;
    private final SearchIndexService indexService;
    private final ReconciliationService reconciliationService;

    /**
     * 消费 outbox 消息，解析合法行并按实体类型更新索引。
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "search-index-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        List<OutboxEvent> events = OutboxMessageReader.read(objectMapper, message);
        if (events.isEmpty()) {
            ack.acknowledge();
            return;
        }
        for (OutboxEvent event : events) {
            OutboxPayload payload = event.parsePayload(objectMapper).orElse(null);
            if (payload == null) {
                continue;
            }
            if ("content_published".equals(payload.text("eventType"))) {
                Long postId = payload.longValue("postId");
                if (postId == null) {
                    continue;
                }
                try {
                    indexService.upsertKnowPostStrict(postId);
                } catch (RuntimeException failure) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.ES_INDEX,
                            ReconciliationTargetType.POST,
                            postId
                    );
                }
                continue;
            }
            Long id = payload.longValue("id");
            if (!"knowpost".equals(payload.text("entity")) || id == null) {
                continue;
            }
            if ("delete".equalsIgnoreCase(payload.text("op"))) {
                indexService.softDeleteKnowPost(id);
            } else {
                indexService.upsertKnowPost(id);
            }
        }
        ack.acknowledge();
    }

}
