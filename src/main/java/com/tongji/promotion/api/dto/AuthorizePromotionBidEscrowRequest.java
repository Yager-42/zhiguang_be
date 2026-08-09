package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.Positive;

/** 出价前授权的最高可用竞价金额。 */
public record AuthorizePromotionBidEscrowRequest(@Positive long amount) {
}
