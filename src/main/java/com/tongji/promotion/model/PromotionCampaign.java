package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 推广活动：创作者为某帖子在某资源位发起的投放语义，承载资源类型与投放时间窗。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionCampaign {
    private long id;
    private long creatorUserId;
    private long postId;
    private PromotionResourceType resourceType;
    private PromotionCampaignStatus status;
    private Instant startAt;
    private Instant endAt;
    private Instant createdAt;
    private Instant updatedAt;
}
