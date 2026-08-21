package com.tongji.capability.api.dto;

/**
 * 前端可见的运行时能力。
 *
 * @param promotionAuction 推广竞价能力
 * @param contentReward 内容奖励能力
 */
public record ApplicationCapabilitiesResponse(
        PromotionAuctionCapability promotionAuction,
        ContentRewardCapability contentReward
) {

    /**
     * 推广竞价运行时能力。
     *
     * @param enabled 是否启用
     * @param transport 启用时使用的传输协议；未启用时为空
     */
    public record PromotionAuctionCapability(boolean enabled, String transport) {
    }

    /**
     * 内容奖励运行时能力。
     *
     * @param enabled 是否启用
     */
    public record ContentRewardCapability(boolean enabled) {
    }
}
