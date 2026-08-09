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
                "event-1", PromotionAuctionRealtimeEvent.RANKING_DELTA, 301L, "d-1",
                2L, 2L, "OPEN", List.of(), Instant.parse("2026-06-20T10:05:00Z"));

        publisher.publishPublic(event);

        verify(template).convertAndSend("/topic/promotion-auctions/301", event);
        verify(nativeHandler).publishPublic(event);
    }

    @Test
    void sendsPrivateOutcomeToUserQueue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        PromotionNativeBidWebSocketHandler nativeHandler = mock(PromotionNativeBidWebSocketHandler.class);
        StompPromotionAuctionRealtimePublisher publisher =
                new StompPromotionAuctionRealtimePublisher(template, nativeHandler);
        PromotionAuctionOutcomeEvent event = new PromotionAuctionOutcomeEvent(
                "event-1", PromotionAuctionOutcomeEvent.BID_CONFIRMED, 301L, 42L,
                "cmd-1", "d-1", 2L, 120L, null, Instant.parse("2026-06-20T10:05:00Z"));

        publisher.publishOutcome(event);

        verify(template).convertAndSendToUser("42", "/queue/promotion-auction-outcomes", event);
        verify(nativeHandler).publishOutcome(event);
    }
}
