package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * WebSocket 推广出价请求；campaign ID 使用字符串避免 JavaScript 大整数精度丢失。
 *
 * @since 2026-08-09
 */
public record PromotionWebSocketBidRequest(
        @NotBlank String campaignId,
        @Positive long bidAmount,
        @NotBlank String idempotencyKey
) {
}
