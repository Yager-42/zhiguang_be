package com.tongji.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/** 创建推广活动请求。resourceType 取 {@code feed_top_slot} / {@code search_top_slot}。 */
public record CreatePromotionCampaignRequest(
        @Positive long postId,
        @NotBlank String resourceType,
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {}
