package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 推广参赛记录：创作者以某篇知文报名系统生成的竞价场次。
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
