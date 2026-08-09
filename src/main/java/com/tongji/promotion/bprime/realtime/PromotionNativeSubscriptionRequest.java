package com.tongji.promotion.bprime.realtime;

/**
 * 原生竞价 WebSocket 的房间订阅请求。
 *
 * @since 2026-08-09
 */
public record PromotionNativeSubscriptionRequest(
        String type,
        long auctionWindowId
) {
    public static final String SUBSCRIBE = "SUBSCRIBE";
}
