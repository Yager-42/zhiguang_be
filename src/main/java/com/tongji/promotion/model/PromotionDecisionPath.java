package com.tongji.promotion.model;

/**
 * 竞价窗口的不可变裁决链路。
 */
public enum PromotionDecisionPath {
    LEGACY_BROKER,
    REDIS_STREAM
}
