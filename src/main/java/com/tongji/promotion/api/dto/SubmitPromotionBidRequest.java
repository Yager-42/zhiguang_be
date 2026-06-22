package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

/** 提交推广出价命令：HTTP 只创建 command，不直接冻结或写入排名。 */
public record SubmitPromotionBidRequest(
        @Positive long bidAmount,
        @NotBlank String idempotencyKey
) {}
