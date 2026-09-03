package com.tongji.promotion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 配置推广竞价固定截止时间专用调度器。
 *
 * <p>该调度器不注册为 Spring 的默认 {@code TaskScheduler}，只承载每个 OPEN 窗口的唯一截止任务。
 * Spring 负责关闭调度器；关闭时取消尚未到期的任务，不等待未来 deadline。</p>
 *
 * @since 2026-09-03
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionDeadlineSchedulerConfig {

    /**
     * 创建具名、可关闭且取消后及时移除任务的截止时间调度器。
     *
     * @param threadCount 固定调度线程数，必须为正数
     * @return 仅供推广竞价 Deadline Manager 使用的调度器
     */
    @Bean(name = "promotionAuctionDeadlineScheduler")
    public ThreadPoolTaskScheduler promotionAuctionDeadlineScheduler(
            @Value("${promotion.bprime.deadline-scheduler-thread-count:1}") int threadCount) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threadCount);
        scheduler.setThreadNamePrefix("promotion-auction-deadline-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
