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
    public void publishOutcome(PromotionAuctionOutcomeEvent event) {
    }
}
