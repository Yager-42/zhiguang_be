package com.tongji.outbox;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Converts one Canal batch into complete outbox-row messages and waits for Kafka acceptance.
 */
@Component
public class CanalOutboxBatchPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final long sendTimeoutMs;

    public CanalOutboxBatchPublisher(KafkaTemplate<String, String> kafka,
                                     ObjectMapper objectMapper,
                                     @Value("${canal.kafka-send-timeout-ms:10000}") long sendTimeoutMs) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public void publish(Message message) throws Exception {
        for (CanalEntry.Entry entry : message.getEntries()) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }

            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            if (eventType != CanalEntry.EventType.INSERT && eventType != CanalEntry.EventType.UPDATE) {
                continue;
            }

            ArrayNode data = objectMapper.createArrayNode();
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                ObjectNode row = objectMapper.createObjectNode();
                for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
                    if (column.getIsNull()) {
                        row.putNull(column.getName());
                    } else {
                        row.put(column.getName(), column.getValue());
                    }
                }
                data.add(row);
            }

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("table", entry.getHeader().getTableName());
            envelope.put("type", eventType.name());
            envelope.set("data", data);

            String payload = objectMapper.writeValueAsString(envelope);
            String partitionKey = partitionKey(data);
            if (partitionKey == null) {
                kafka.send(OutboxTopics.CANAL_OUTBOX, payload).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } else {
                kafka.send(OutboxTopics.CANAL_OUTBOX, partitionKey, payload)
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    private String partitionKey(ArrayNode data) {
        if (data.size() != 1) {
            return null;
        }
        ObjectNode row = (ObjectNode) data.get(0);
        String aggregateType = row.path("aggregate_type").asText("");
        String aggregateId = row.path("aggregate_id").asText("");
        if (aggregateType.isBlank() || aggregateId.isBlank()) {
            return null;
        }
        return aggregateType + ":" + aggregateId;
    }
}
