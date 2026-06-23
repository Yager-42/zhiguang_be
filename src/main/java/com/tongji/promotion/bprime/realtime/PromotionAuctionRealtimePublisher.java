package com.tongji.promotion.bprime.realtime;

public interface PromotionAuctionRealtimePublisher {

    void publishPublic(PromotionAuctionRealtimeEvent event);

    void publishOutcome(PromotionAuctionOutcomeEvent event);
}
