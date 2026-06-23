package com.tongji.promotion.bprime.realtime;

public final class PromotionAuctionRealtimeChannels {

    public static final String ENDPOINT = "/ws/promotion-auction";
    public static final String PUBLIC_TOPIC_PREFIX = "/topic/promotion-auctions/";
    public static final String PRIVATE_OUTCOME_QUEUE = "/queue/promotion-auction-outcomes";

    private PromotionAuctionRealtimeChannels() {
    }

    public static String publicTopic(long auctionWindowId) {
        return PUBLIC_TOPIC_PREFIX + auctionWindowId;
    }
}
