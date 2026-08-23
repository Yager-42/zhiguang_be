package com.tongji.promotion.api.dto;

import java.util.List;

/** 当前用户推广活动分页响应。 */
public record PromotionCampaignListResponse(
        List<PromotionCampaignResponse> items,
        int limit,
        int offset,
        boolean hasMore
) {
}
