package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** 报名当前系统竞价场次；参赛记录由服务端按场次幂等生成。 */
public record EnterCurrentPromotionAuctionRequest(
        @Positive long postId,
        @NotBlank String resourceType
) {
}
