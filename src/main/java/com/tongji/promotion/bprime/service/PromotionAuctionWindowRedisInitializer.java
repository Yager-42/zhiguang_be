package com.tongji.promotion.bprime.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * MySQL 窗口事务提交后初始化 Redis 热状态。
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionWindowRedisInitializer {

    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;

    public PromotionAuctionWindowRedisInitializer(PromotionAuctionHotStateLifecycle hotStateLifecycle) {
        this.hotStateLifecycle = hotStateLifecycle;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void initialize(PromotionAuctionWindowCreatedEvent event) {
        hotStateLifecycle.activateWindow(event.window());
    }
}
