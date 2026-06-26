package com.tongji.counter.event;

import lombok.Data;

import java.util.UUID;

/**
 * 计数事件模型。
 *
 * <p>用于描述一次状态变化导致的计数增量，例如点赞 +1 / 取消点赞 -1。
 * eventId 只用于抵抗同一次 Kafka 事件的重复投递，不参与业务去重语义。</p>
 */
@Data
public class CounterEvent {
    private String eventId;
    private Long occurredAt;
    private String entityType;
    private String entityId;
    private String metric;
    private int idx;
    private long userId;
    private int delta;

    public CounterEvent() {
    }

    public CounterEvent(String eventId, Long occurredAt, String entityType, String entityId,
                        String metric, int idx, long userId, int delta) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metric = metric;
        this.idx = idx;
        this.userId = userId;
        this.delta = delta;
    }

    public static CounterEvent of(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        return new CounterEvent(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                entityType,
                entityId,
                metric,
                idx,
                userId,
                delta
        );
    }
}
