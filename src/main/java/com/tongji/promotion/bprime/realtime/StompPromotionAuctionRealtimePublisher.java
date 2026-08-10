package com.tongji.promotion.bprime.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class StompPromotionAuctionRealtimePublisher implements PromotionAuctionRealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final PromotionNativeBidWebSocketHandler nativeWebSocketHandler;

    public StompPromotionAuctionRealtimePublisher(
            SimpMessagingTemplate messagingTemplate,
            PromotionNativeBidWebSocketHandler nativeWebSocketHandler) {
        this.messagingTemplate = messagingTemplate;
        this.nativeWebSocketHandler = nativeWebSocketHandler;
    }

    @Override
    public void publishPublic(PromotionAuctionRealtimeEvent event) {
        nativeWebSocketHandler.publishPublic(event);
        messagingTemplate.convertAndSend(PromotionAuctionRealtimeChannels.publicTopic(event.auctionWindowId()), event);
    }

    @Override
    public int publicSubscriberCount(long auctionWindowId) {
        return nativeWebSocketHandler.subscriberCount(auctionWindowId);
    }

    @Override
    public int pendingPublicMessageCount(long auctionWindowId) {
        return nativeWebSocketHandler.pendingPublicMessageCount(auctionWindowId);
    }

}
