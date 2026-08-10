package com.tongji.promotion.bprime.redis;

/**
 * Redis 竞价权威不可用或状态不一致时抛出的暂停信号。
 */
public class PromotionAuctionUnavailableException extends RuntimeException {

    public PromotionAuctionUnavailableException(String message) {
        super(message);
    }

    public PromotionAuctionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
