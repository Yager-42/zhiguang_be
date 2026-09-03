package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在应用启动阶段恢复所有 MySQL OPEN 推广竞价窗口的固定 deadline。
 *
 * <p>恢复失败会直接中止应用启动，避免实例在窗口缺少 timer 时对外提供 bprime 收单能力。</p>
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionBPrimeStartupRecovery implements ApplicationRunner {

    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;
    private final PromotionAuctionDeadlineManager deadlineManager;
    private final PromotionAuctionAvailabilityGate availabilityGate;

    /**
     * 创建 bprime 启动恢复入口。
     *
     * @param hotStateLifecycle OPEN 热状态围栏和 Stream registry 恢复器
     * @param deadlineManager OPEN 窗口 deadline 恢复器
     * @param availabilityGate 进程内收单闸门
     */
    public PromotionBPrimeStartupRecovery(PromotionAuctionHotStateLifecycle hotStateLifecycle,
                                          PromotionAuctionDeadlineManager deadlineManager,
                                          PromotionAuctionAvailabilityGate availabilityGate) {
        this.hotStateLifecycle = hotStateLifecycle;
        this.deadlineManager = deadlineManager;
        this.availabilityGate = availabilityGate;
    }

    /**
     * 先验证既有 OPEN 热状态并恢复 Stream registry，再恢复全部 deadline；只有全成功后收单。
     *
     * @param args Spring Boot 启动参数，本恢复过程不读取参数
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            hotStateLifecycle.recoverActiveWindows();
            deadlineManager.recoverOpenWindows();
            availabilityGate.acceptAfterStartupRecovery();
        } catch (RuntimeException exception) {
            availabilityGate.pause();
            throw exception;
        }
    }
}
