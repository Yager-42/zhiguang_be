package com.tongji.promotion.bprime.redis;

import java.util.ArrayList;
import java.util.List;

public final class PromotionAuctionRedisKeys {

    private PromotionAuctionRedisKeys() {
    }

    public static String activeStreams() {
        return "promotion:auction:active-streams";
    }

    /** 关窗索引：member=windowId，score=windowEndAtEpochMs（Go auction:active 同构）。 */
    public static String closingIndex() {
        return "promotion:auction:closing";
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

    /** 幂等命令记录：单 key Hash，字段级 TTL（HSETEX），无时间桶。 */
    public static String commandBucket(long auctionWindowId) {
        return prefix(auctionWindowId) + ":commands";
    }

    public static String campaign(long auctionWindowId, long campaignId) {
        return prefix(auctionWindowId) + ":campaign:" + campaignId;
    }

    public static List<String> decisionKeys(long auctionWindowId, long campaignId) {
        return List.of(
                state(auctionWindowId),
                commandBucket(auctionWindowId),
                ranking(auctionWindowId),
                campaign(auctionWindowId, campaignId),
                escrow(auctionWindowId),
                events(auctionWindowId),
                publicationChannel(auctionWindowId),
                publicationWakeup(auctionWindowId));
    }

    public static List<String> initializationKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), ranking(auctionWindowId), escrow(auctionWindowId),
                events(auctionWindowId));
    }

    public static List<String> closeKeys(long auctionWindowId) {
        return List.of(state(auctionWindowId), events(auctionWindowId), publicationChannel(auctionWindowId));
    }
}
