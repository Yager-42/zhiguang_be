package com.tongji.promotion.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tongji.promotion.model.PromotionSlotAllocation;

/**
 * 位分配读路径视图：feed/search 商业位插入与商业标识的来源。
 * 字段以字符串暴露，便于直接填入 {@code FeedItemResponse} 与缓存 JSON 序列化。
 */
public record PromotionAllocationView(
        String postId,
        String placementType,
        String promotionCampaignId,
        String auctionWindowId
) {
    public static PromotionAllocationView from(PromotionSlotAllocation allocation) {
        return new PromotionAllocationView(
                String.valueOf(allocation.getPostId()),
                allocation.getResourceType().placement(),
                String.valueOf(allocation.getCampaignId()),
                String.valueOf(allocation.getAuctionWindowId())
        );
    }

    @JsonIgnore
    public long postIdAsLong() {
        return Long.parseLong(postId);
    }
}
