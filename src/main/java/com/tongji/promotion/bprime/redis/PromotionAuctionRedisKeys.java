package com.tongji.promotion.bprime.redis;

import java.util.List;

public final class PromotionAuctionRedisKeys {

    private PromotionAuctionRedisKeys() {
    }

    public static List<String> decisionKeys(long auctionWindowId, long campaignId) {
        String prefix = "promotion:auction:" + auctionWindowId;
        return List.of(
                prefix + ":state",
                prefix + ":commands",
                prefix + ":ranking",
                prefix + ":campaign:" + campaignId,
                prefix + ":decision_version"
        );
    }
}
