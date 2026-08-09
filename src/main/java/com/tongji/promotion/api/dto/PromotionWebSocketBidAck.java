package com.tongji.promotion.api.dto;

/**
 * WebSocket 推广出价的当前用户私有确认。
 *
 * @since 2026-08-09
 */
public record PromotionWebSocketBidAck(
        String idempotencyKey,
        String commandId,
        String auctionWindowId,
        String status,
        boolean resultAvailable,
        String rejectionReason
) {

    /** 将统一收单结果转换为 WebSocket ACK。 */
    public static PromotionWebSocketBidAck from(
            String idempotencyKey,
            SubmitPromotionBidCommandResponse response) {
        return new PromotionWebSocketBidAck(idempotencyKey, response.commandId(), response.auctionWindowId(),
                response.status(), response.resultAvailable(), response.rejectionReason());
    }

    /** 创建没有进入权威命令链路的协议或业务拒绝 ACK。 */
    public static PromotionWebSocketBidAck rejected(String idempotencyKey, String rejectionReason) {
        return new PromotionWebSocketBidAck(idempotencyKey, null, null, "REJECTED", true, rejectionReason);
    }
}
