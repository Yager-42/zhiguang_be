package com.tongji.counter.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 计数事件生产者。
 *
 * <p>职责：将业务产生的计数增量事件异步发送到 Kafka 主题，供聚合消费者处理。</p>
 */
@Service
public class CounterEventProducer {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public CounterEventProducer(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布计数事件到 Kafka。
     * @param event 计数事件（实体类型、ID、指标、delta 等）
     */
    public void publish(CounterEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafka.send(CounterTopics.EVENTS, payload); // 异步写入计数事件主题（幂等生产已在配置启用）
        } catch (JsonProcessingException e) {
            // 生产异常不抛出影响主流程；可接入告警
        }
    }

    public void publishReliable(CounterEvent event) {
        publishReliable(event, event.getEntityId());
    }

    /**
     * 使用调用方指定的业务键可靠发布计数事件。
     *
     * @param event 计数事件
     * @param partitionKey Kafka 分区键；同一业务关系必须保持稳定
     */
    public void publishReliable(CounterEvent event, String partitionKey) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafka.send(CounterTopics.EVENTS, partitionKey, payload).join();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("counter event serialization failed", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("counter event publish failed", exception);
        }
    }
}
