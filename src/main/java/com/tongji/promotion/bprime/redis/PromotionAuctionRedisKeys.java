package com.tongji.promotion.bprime.redis;

import java.util.ArrayList;
import java.util.List;

public final class PromotionAuctionRedisKeys {

    private PromotionAuctionRedisKeys() {
    }

    public static String activeStreams() {
        return "promotion:auction:active-streams";
    }

    public static String prefix(long auctionWindowId) {
        return "promotion:auction:{" + auctionWindowId + "}";
    }

    public static String state(long auctionWindowId) {
        return prefix(auctionWindowId) + ":state";
    }

    public static String ranking(long auctionWindowId) {
        return prefix(auctionWindowId) + ":ranking";
    }

    public static String escrow(long auctionWindowId) {
        return prefix(auctionWindowId) + ":escrow";
    }

    public static String events(long auctionWindowId) {
        return prefix(auctionWindowId) + ":events";
    }

    public static String publicationChannel(long auctionWindowId) {
        return prefix(auctionWindowId) + ":pub";
    }
    public static String publicationWakeup(long auctionWindowId) {
        return prefix(auctionWindowId) + ":wakeup";
    }

    public static String publicationPattern() {
        return "promotion:auction:{*}:pub";
    }

    public static String commandBucket(long auctionWindowId, long minuteBucket) {
        return prefix(auctionWindowId) + ":commands:" + minuteBucket;
    }

    public static String campaign(long auctionWindowId, long campaignId) {
        return prefix(auctionWindowId) + ":campaign:" + campaignId;
    }

    public static List<String> decisionKeys(long auctionWindowId, long campaignId,
                                            long currentCommandBucket, int previousCommandBucketCount) {
        List<String> keys = new ArrayList<>(8 + previousCommandBucketCount);
        keys.add(state(auctionWindowId));
        keys.add(commandBucket(auctionWindowId, currentCommandBucket));
        keys.add(ranking(auctionWindowId));
        keys.add(campaign(auctionWindowId, campaignId));
        keys.add(escrow(auctionWindowId));
        keys.add(events(auctionWindowId));
        keys.add(publicationChannel(auctionWindowId));
        keys.add(publicationWakeup(auctionWindowId));
        for (int offset = 1; offset <= previousCommandBucketCount; offset++) {
            keys.add(commandBucket(auctionWindowId, currentCommandBucket - offset));
        }
        return keys;
    }

    public static List<String> initializationKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), ranking(auctionWindowId), escrow(auctionWindowId),
                events(auctionWindowId));
    }

    public static List<String> closeKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), events(auctionWindowId), publicationChannel(auctionWindowId));
    }
}
