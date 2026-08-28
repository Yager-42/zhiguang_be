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
 * 将 Canal 批次转换为完整 Outbox 行 envelope，并等待 Kafka Producer 给出终态结果。
 *
 * <p>发送等待时间必须大于 Producer 的 {@code delivery.timeout.ms}，避免 Bridge 回滚时原发送仍长期在途。</p>
 *
 * @since 2026-08-28
 */
@Component
public class CanalOutboxBatchPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final long sendTimeoutMs;

    public CanalOutboxBatchPublisher(KafkaTemplate<String, String> kafka,
                                     ObjectMapper objectMapper,
                                     @Value("${canal.kafka-send-timeout-ms:35000}") long sendTimeoutMs) {
        if (sendTimeoutMs <= 0L) {
            throw new IllegalArgumentException("Canal Kafka send timeout must be positive");
        }
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * 顺序发布一个 Canal 批次中的有效 Outbox 行；任一发送失败时抛出异常，由 Bridge 回滚整批。
     *
     * @param message Canal 批次
     * @throws Exception 当解析、序列化、Kafka 入队或 broker 投递失败时
     */
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
