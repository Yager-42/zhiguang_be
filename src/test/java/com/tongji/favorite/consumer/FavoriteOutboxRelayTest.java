package com.tongji.favorite.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.favorite.event.FavoriteChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FavoriteOutboxRelayTest {

    @Test
    void relaysCommittedFavoriteWithStableEventIdAndRelationKey() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CounterEventProducer producer = mock(CounterEventProducer.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        FavoriteOutboxRelay relay = new FavoriteOutboxRelay(objectMapper, producer);
        String favoritePayload = objectMapper.writeValueAsString(
                new FavoriteChangedEvent("9001", FavoriteChangedEvent.TYPE, 1,
                        42L, 101L, true, 1, 1787306400000L));
        String message = objectMapper.createObjectNode()
                .put("table", "outbox")
                .put("type", "INSERT")
                .set("data", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("id", "9001")
                        .put("aggregate_type", "user_favorite")
                        .put("aggregate_id", "42")
                        .put("type", "FavoriteChanged")
                        .put("payload", favoritePayload)
                        .put("created_at", "2026-08-21 10:00:00.000")))
                .toString();

        relay.onMessage(message, acknowledgment);

        ArgumentCaptor<CounterEvent> event = ArgumentCaptor.forClass(CounterEvent.class);
        verify(producer).publishReliable(event.capture(), org.mockito.ArgumentMatchers.eq("42:101"));
        assertThat(event.getValue().getEventId()).isEqualTo("9001");
        assertThat(event.getValue().getMetric()).isEqualTo("fav");
        assertThat(event.getValue().getDelta()).isEqualTo(1);
        verify(acknowledgment).acknowledge();
    }
}
