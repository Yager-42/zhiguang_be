package com.tongji.promotion.bprime.redis;

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


    public static String campaign(long auctionWindowId, long campaignId) {
        return prefix(auctionWindowId) + ":campaign:" + campaignId;
    }

    /**
     * 构造 Cluster-safe 批量裁决 KEYS：公共六键后接批内去重后的 campaign key。
     *
     * @param auctionWindowId 拍卖窗口 ID
     * @param campaignIds 按首次出现顺序去重的 campaign ID
     * @return 全部包含相同窗口 hash tag 的 Redis key
     */
    public static List<String> decisionKeys(long auctionWindowId, List<Long> campaignIds) {
        List<String> keys = new java.util.ArrayList<>(6 + campaignIds.size());
        keys.add(state(auctionWindowId));
        keys.add(ranking(auctionWindowId));
        keys.add(escrow(auctionWindowId));
        keys.add(events(auctionWindowId));
        keys.add(publicationChannel(auctionWindowId));
        keys.add(publicationWakeup(auctionWindowId));
        for (Long campaignId : campaignIds) {
            keys.add(campaign(auctionWindowId, campaignId));
        }
        return List.copyOf(keys);
    }

    public static List<String> initializationKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), ranking(auctionWindowId), escrow(auctionWindowId),
                events(auctionWindowId));
    }

    public static List<String> closeKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), events(auctionWindowId), publicationChannel(auctionWindowId));
    }
}
