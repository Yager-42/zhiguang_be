package com.tongji.promotion.bprime.realtime;

/**
 * 原生竞价 WebSocket 已完成房间订阅的确认。
 *
 * @since 2026-08-09
 */
public record PromotionNativeSubscriptionAck(
        String eventType,
        String auctionWindowId
) {
    public static final String SUBSCRIBED = "SUBSCRIBED";
}
