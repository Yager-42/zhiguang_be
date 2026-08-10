package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.redis.PromotionAuctionHotStateRepository;
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

    private final PromotionAuctionHotStateRepository hotStateRepository;

    public PromotionAuctionWindowRedisInitializer(PromotionAuctionHotStateRepository hotStateRepository) {
        this.hotStateRepository = hotStateRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void initialize(PromotionAuctionWindowCreatedEvent event) {
        hotStateRepository.initialize(event.window(), 0L);
    }
}
