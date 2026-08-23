package com.tongji.promotion.model;

/**
 * 推广参赛记录的可展示结论。
 *
 * <p>该结论不保存竞价名次：结算生成的位分配即为胜出事实；未生成位分配且场次已结束时为未胜出。</p>
 */
public enum PromotionParticipationOutcome {
    PENDING,
    WON,
    NOT_WON
}
