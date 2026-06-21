package com.tongji.promotion.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tongji.promotion.model.PromotionSlotAllocation;

import java.time.Instant;

/**
 * 位分配读路径视图：feed/search 商业位插入与商业标识的来源。
 * 字段以字符串暴露，便于直接填入 {@code FeedItemResponse} 与缓存 JSON 序列化。
 * <p>{@code allocationStartAt}/{@code allocationEndAt} 随缓存值一起序列化，读路径据此
 * 过滤掉窗口已切走、却仍残留在缓存里的过期 allocation。</p>
 */
public record PromotionAllocationView(
        String postId,
        String placementType,
        String promotionCampaignId,
        String auctionWindowId,
        Instant allocationStartAt,
        Instant allocationEndAt
) {
    /** 远古起点 / 远未来终点：4 参便捷构造用于不关心有效期的测试 fixture。 */
    private static final Instant ALWAYS_ACTIVE_START = Instant.EPOCH;
    private static final Instant ALWAYS_ACTIVE_END = Instant.parse("9999-12-31T23:59:59Z");

    public PromotionAllocationView(String postId, String placementType, String promotionCampaignId, String auctionWindowId) {
        this(postId, placementType, promotionCampaignId, auctionWindowId, ALWAYS_ACTIVE_START, ALWAYS_ACTIVE_END);
    }

    public static PromotionAllocationView from(PromotionSlotAllocation allocation) {
        return new PromotionAllocationView(
                String.valueOf(allocation.getPostId()),
                allocation.getResourceType().placement(),
                String.valueOf(allocation.getCampaignId()),
                String.valueOf(allocation.getAuctionWindowId()),
                allocation.getAllocationStartAt(),
                allocation.getAllocationEndAt()
        );
    }

    @JsonIgnore
    public long postIdAsLong() {
        return Long.parseLong(postId);
    }

    /** 当前时刻是否落在 [allocationStartAt, allocationEndAt) 有效期内。 */
    @JsonIgnore
    public boolean isActiveAt(Instant now) {
        return now != null
                && allocationStartAt != null && allocationEndAt != null
                && !now.isBefore(allocationStartAt)
                && now.isBefore(allocationEndAt);
    }
}
