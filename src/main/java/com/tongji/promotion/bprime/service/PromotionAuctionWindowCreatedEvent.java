package com.tongji.promotion.bprime.service;

import com.tongji.promotion.model.PromotionAuctionWindow;

/**
 * 新 Redis Stream 竞价窗口已在 MySQL 中创建。
 */
public record PromotionAuctionWindowCreatedEvent(PromotionAuctionWindow window) {
}
