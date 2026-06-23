package com.tongji.promotion.bprime.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(PromotionAuctionRealtimePublisher.class)
public class NoopPromotionAuctionRealtimePublisher implements PromotionAuctionRealtimePublisher {

    @Override
    public void publishPublic(PromotionAuctionRealtimeEvent event) {
    }

    @Override
    public void publishOutcome(PromotionAuctionOutcomeEvent event) {
    }
}
