package com.tongji.promotion.bprime.realtime;

public interface PromotionAuctionRealtimePublisher {

    void publishPublic(PromotionAuctionRealtimeEvent event);

    int publicSubscriberCount(long auctionWindowId);

    int pendingPublicMessageCount(long auctionWindowId);
}
