package com.tongji.relation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxTopics;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.processor.RelationEventProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Canal Outbox 消费者。
 * 职责：消费 Canal 桥接写入的 outbox 主题消息，提取 payload 并反序列化为 RelationEvent，交由处理器落库与更新缓存/计数；使用手动位点确保处理成功语义。
 */
@Service
public class CanalOutboxConsumer {
    private final OutboxMessageReader messageReader;
    private final ObjectMapper objectMapper;
    private final RelationEventProcessor processor;
    private final TaskExecutor taskExecutor;

    /**
     * Outbox 消费者构造函数。
     * @param objectMapper JSON 序列化器
     * @param processor 关系事件处理器
     * @param taskExecutor 关系事件执行器
     */
    public CanalOutboxConsumer(OutboxMessageReader messageReader,
                               ObjectMapper objectMapper,
                               RelationEventProcessor processor,
                               @Qualifier("relationEventExecutor") TaskExecutor taskExecutor) {
        this.messageReader = messageReader;
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
        List<RelationEvent> events = messageReader.read(message).stream()
                .map(event -> event.payloadAs(objectMapper, RelationEvent.class))
                .flatMap(java.util.Optional::stream)
                .toList();
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

        try {
            completionLatch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (failure.get() == null) {
            ack.acknowledge();
        }
    }
}
