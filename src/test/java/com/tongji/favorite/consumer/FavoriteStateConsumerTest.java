package com.tongji.favorite.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.service.CounterService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FavoriteStateConsumerTest {

    @Test
    void projectsFavoriteAbsoluteStateAndAcknowledges() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CounterService counterService = mock(CounterService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        FavoriteStateConsumer consumer = new FavoriteStateConsumer(objectMapper, counterService);
        CounterEvent event = new CounterEvent("9001", 1787306400000L, "knowpost", "101", "fav", 2, 42L, -1);

        consumer.onMessage(objectMapper.writeValueAsString(event), acknowledgment);

        verify(counterService).applyFavoriteState("knowpost", "101", 42L, false);
        verify(acknowledgment).acknowledge();
    }
}
