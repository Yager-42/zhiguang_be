package com.tongji.promotion.model;

/**
 * 推广活动及其创建者公开展示信息。
 *
 * @param campaign 推广活动事实，不为 {@code null}
 * @param creatorNickname 创建者当前昵称；资料缺失时使用稳定兜底文案
 * @param participationOutcome 由窗口结算与推荐位分配推导的参赛结论
 * @param allocation 胜出后生成的实际推荐位分配；未胜出或待结算时为 {@code null}
 */
public record PromotionCampaignDetails(
        PromotionCampaign campaign,
        String creatorNickname,
        PromotionParticipationOutcome participationOutcome,
        PromotionSlotAllocation allocation
) {
}
