package com.tongji.promotion.bprime.redis;

import java.util.List;

public final class PromotionAuctionRedisKeys {

    private PromotionAuctionRedisKeys() {
    }

    public static String prefix(long auctionWindowId) {
        return "promotion:auction:{" + auctionWindowId + "}";
    }

    public static List<String> decisionKeys(long auctionWindowId, long campaignId) {
        String prefix = prefix(auctionWindowId);
        return List.of(
                prefix + ":state",
                prefix + ":commands",
                prefix + ":ranking",
                prefix + ":campaign:" + campaignId,
                prefix + ":escrow"
        );
    }
}
