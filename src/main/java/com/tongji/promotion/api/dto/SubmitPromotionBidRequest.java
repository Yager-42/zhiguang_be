package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.Positive;

/** 提交推广出价请求：申报价（虚拟货币），接单即冻结。 */
public record SubmitPromotionBidRequest(
        @Positive long bidAmount
) {}
