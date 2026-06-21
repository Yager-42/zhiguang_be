package com.tongji.promotion.model;

/** boost 活动状态：创建即 ACTIVE（预算已冻结），到期结算后 CLOSED（剩余预算已释放）。 */
public enum PaidBoostCampaignStatus {
    ACTIVE,
    CLOSED
}
