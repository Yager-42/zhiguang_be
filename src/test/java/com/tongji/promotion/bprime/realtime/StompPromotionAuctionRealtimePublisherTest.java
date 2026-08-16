package com.tongji.promotion.bprime.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StompPromotionAuctionRealtimePublisherTest {

    @Test
    void sendsPublicEventToWindowTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        PromotionNativeBidWebSocketHandler nativeHandler = mock(PromotionNativeBidWebSocketHandler.class);
        StompPromotionAuctionRealtimePublisher publisher =
                new StompPromotionAuctionRealtimePublisher(template, nativeHandler);
        PromotionAuctionRealtimeEvent event = new PromotionAuctionRealtimeEvent(
                "event-1", PromotionAuctionRealtimeEvent.RANKING_DELTA, "301", "d-1",
                2L, 2L, "OPEN", List.of(), Instant.parse("2026-06-20T10:05:00Z"));

        publisher.publishPublic(event);

        verify(template).convertAndSend("/topic/promotion-auctions/301", event);
        verify(nativeHandler).publishPublic(event);
    }

}
