package com.tongji.relation.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.processor.RelationEventProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import com.tongji.common.util.OutboxMessageUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数；使用手动位点确保处理成功语义。
 */
@Service
public class CanalOutboxConsumer {
    private final ObjectMapper objectMapper;
    private final RelationEventProcessor processor;
    private final TaskExecutor taskExecutor;

    /**
     * Outbox 消费者构造函数。
     * @param objectMapper JSON 序列化器
     * @param processor 关系事件处理器
     * @param taskExecutor 关系事件执行器
     */
    public CanalOutboxConsumer(ObjectMapper objectMapper,
                               RelationEventProcessor processor,
                               @Qualifier("relationEventExecutor") TaskExecutor taskExecutor) {
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 消费 Canal outbox 消息并转为关系事件处理。
     * 监听 Canal→Kafka 桥接写入的 outbox 主题；使用手动位点提交
     * @param message Kafka 消息内容
     * @param ack 位点确认对象
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "relation-outbox-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            if (rows.isEmpty()) {
                ack.acknowledge();
                return;
            }
            List<RelationEvent> events = new ArrayList<>();
            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                if (payloadNode == null) {
                    continue;
                }

                events.add(objectMapper.readValue(payloadNode.asText(), RelationEvent.class));
            }
            if (events.isEmpty()) {
                ack.acknowledge();
                return;
            }

            CountDownLatch completionLatch = new CountDownLatch(events.size());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (RelationEvent event : events) {
                try {
                    taskExecutor.execute(() -> {
                        try {
                            processor.process(event);
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    while (completionLatch.getCount() > 0) {
                        completionLatch.countDown();
                    }
                    break;
                }
            }

            completionLatch.await();
            if (failure.get() == null) {
                ack.acknowledge();
            }
        } catch (Exception ignored) {}
    }
}
