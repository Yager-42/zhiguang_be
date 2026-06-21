package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 创建 boost 活动请求。
 * <p>{@code channel} 取 {@code home_recommendation} / {@code follow_delivery}；
 * 创作者提交 {@code bidAmount}（出价），有效 boost 值由服务端 quote 产出，不由客户端提交。</p>
 */
public record CreatePaidBoostCampaignRequest(
        @Positive long postId,
        @NotBlank String channel,
        @Positive long bidAmount,
        @Positive long unitPrice,
        @Positive long budgetTotal,
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {}
