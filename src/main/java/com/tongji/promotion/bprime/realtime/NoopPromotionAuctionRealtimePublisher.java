package com.tongji.promotion.bprime.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "false", matchIfMissing = true)
public class NoopPromotionAuctionRealtimePublisher implements PromotionAuctionRealtimePublisher {

    @Override
    public void publishPublic(PromotionAuctionRealtimeEvent event) {
    }

    @Override
    public int publicSubscriberCount(long auctionWindowId) {
        return 0;
    }

    @Override
    public int pendingPublicMessageCount(long auctionWindowId) {
        return 0;
    }

}
