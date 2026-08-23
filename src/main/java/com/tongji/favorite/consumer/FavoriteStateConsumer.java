package com.tongji.favorite.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterTopics;
import com.tongji.counter.service.CounterService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 将收藏事件中的绝对状态幂等投影到 Redis Bitmap。
 *
 * @since 2026-08-21
 */
@Service
public class FavoriteStateConsumer {
    private final ObjectMapper objectMapper;
    private final CounterService counterService;

    public FavoriteStateConsumer(ObjectMapper objectMapper, CounterService counterService) {
        this.objectMapper = objectMapper;
        this.counterService = counterService;
    }

    /**
     * 消费收藏计数事件；非知文收藏事件只确认不处理。
     *
     * @param message 计数事件 JSON
     * @param acknowledgment Kafka 手动确认句柄
     * @throws Exception JSON 无法解析或 Redis 更新失败时抛出，以触发消息重试
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "favorite-state-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class);
        if ("knowpost".equals(event.getEntityType()) && "fav".equals(event.getMetric())) {
            if (event.getDelta() != 1 && event.getDelta() != -1) {
                throw new IllegalStateException("invalid favorite counter delta");
            }
            counterService.applyFavoriteState(
                    event.getEntityType(),
                    event.getEntityId(),
                    event.getUserId(),
                    event.getDelta() > 0
            );
        }
        acknowledgment.acknowledge();
    }
}
