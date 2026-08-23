package com.tongji.favorite.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.favorite.event.FavoriteChangedEvent;
import com.tongji.favorite.service.FavoriteService;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 将已提交的收藏 Outbox 事件可靠转换为现有计数事件。
 *
 * <p>转发失败时不确认 Canal 消息；重投沿用同一 {@code eventId}，由下游幂等吸收。</p>
 *
 * @since 2026-08-21
 */
@Service
public class FavoriteOutboxRelay {
    private final ObjectMapper objectMapper;
    private final CounterEventProducer counterEventProducer;

    public FavoriteOutboxRelay(ObjectMapper objectMapper, CounterEventProducer counterEventProducer) {
        this.objectMapper = objectMapper;
        this.counterEventProducer = counterEventProducer;
    }

    /**
     * 转发一个 Canal Outbox 批次，并在全部收藏事件被 Kafka 接受后确认消息。
     *
     * @param message Canal Outbox JSON
     * @param acknowledgment Kafka 手动确认句柄
     */
    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "favorite-outbox-relay")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        for (OutboxEvent envelope : OutboxMessageReader.read(objectMapper, message)) {
            if (!FavoriteService.AGGREGATE_TYPE.equals(envelope.aggregateType())
                    || !FavoriteService.EVENT_TYPE.equals(envelope.type())) {
                continue;
            }
            FavoriteChangedEvent favoriteEvent = envelope.payloadAs(objectMapper, FavoriteChangedEvent.class)
                    .orElseThrow(() -> new IllegalStateException("invalid favorite outbox payload"));
            validate(favoriteEvent);
            CounterEvent counterEvent = new CounterEvent(
                    favoriteEvent.eventId(),
                    favoriteEvent.occurredAt(),
                    "knowpost",
                    String.valueOf(favoriteEvent.postId()),
                    "fav",
                    CounterSchema.IDX_FAV,
                    favoriteEvent.userId(),
                    favoriteEvent.delta()
            );
            counterEventProducer.publishReliable(counterEvent, partitionKey(favoriteEvent));
        }
        acknowledgment.acknowledge();
    }

    private void validate(FavoriteChangedEvent event) {
        boolean validState = event.delta() == (event.faved() ? 1 : -1);
        if (event.eventId() == null || event.eventId().isBlank()
                || !FavoriteChangedEvent.TYPE.equals(event.eventType())
                || event.schemaVersion() != FavoriteChangedEvent.CURRENT_SCHEMA_VERSION
                || event.userId() <= 0 || event.postId() <= 0 || !validState) {
            throw new IllegalStateException("invalid favorite outbox event");
        }
    }

    private String partitionKey(FavoriteChangedEvent event) {
        return event.userId() + ":" + event.postId();
    }
}
