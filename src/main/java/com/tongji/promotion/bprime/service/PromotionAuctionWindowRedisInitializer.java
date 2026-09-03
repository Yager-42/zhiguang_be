package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * MySQL 窗口事务提交后依次初始化 Redis 热状态并注册固定 deadline。
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionWindowRedisInitializer {

    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;
    private final PromotionAuctionDeadlineManager deadlineManager;
    private final PromotionAuctionAvailabilityGate availabilityGate;

    /**
     * 创建窗口提交后热状态与 deadline 的顺序编排器。
     *
     * @param hotStateLifecycle Redis 热状态生命周期
     * @param deadlineManager JVM deadline 注册入口
     * @param availabilityGate 进程内收单闸门
     */
    public PromotionAuctionWindowRedisInitializer(PromotionAuctionHotStateLifecycle hotStateLifecycle,
                                                  PromotionAuctionDeadlineManager deadlineManager,
                                                  PromotionAuctionAvailabilityGate availabilityGate) {
        this.hotStateLifecycle = hotStateLifecycle;
        this.deadlineManager = deadlineManager;
        this.availabilityGate = availabilityGate;
    }

    /**
     * 激活窗口热状态并注册 deadline。任一步骤失败都保留已存在的恢复证据并暂停当前进程，
     * 由下一次完整启动围栏验证并恢复。
     *
     * @param event 已提交的窗口创建事件
     * @throws RuntimeException 当热状态激活或 deadline 注册失败时
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void initialize(PromotionAuctionWindowCreatedEvent event) {
        try {
            hotStateLifecycle.activateWindow(event.window());
            deadlineManager.schedule(event.window());
        } catch (RuntimeException activationFailure) {
            availabilityGate.pause();
            throw activationFailure;
        }
    }
}
