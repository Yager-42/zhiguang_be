package com.tongji.promotion.bprime.realtime;

public final class PromotionAuctionRealtimeChannels {

    public static final String ENDPOINT = "/ws/promotion-auction";
    public static final String NATIVE_ENDPOINT = "/ws/promotion-auction-native";
    public static final String APPLICATION_PREFIX = "/app";
    public static final String BID_APPLICATION_DESTINATION = "/promotion-auctions/bids";
    public static final String PUBLIC_TOPIC_PREFIX = "/topic/promotion-auctions/";
    public static final String PRIVATE_BID_ACK_QUEUE = "/queue/promotion-auction-bid-acks";

    private PromotionAuctionRealtimeChannels() {
    }

    public static String publicTopic(long auctionWindowId) {
        return PUBLIC_TOPIC_PREFIX + auctionWindowId;
    }
}
