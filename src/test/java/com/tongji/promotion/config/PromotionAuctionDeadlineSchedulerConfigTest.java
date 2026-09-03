package com.tongji.promotion.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionAuctionDeadlineSchedulerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PromotionAuctionDeadlineSchedulerConfig.class)
            .withPropertyValues(
                    "promotion.bprime.enabled=true",
                    "promotion.bprime.deadline-scheduler-thread-count=2");

    @Test
    void createsDedicatedNamedSchedulerAndClosesItWithContext() {
        AtomicReference<ThreadPoolTaskScheduler> schedulerReference = new AtomicReference<>();

        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    "promotionAuctionDeadlineScheduler", ThreadPoolTaskScheduler.class);
            ScheduledThreadPoolExecutor executor = scheduler.getScheduledThreadPoolExecutor();
            schedulerReference.set(scheduler);

            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("promotion-auction-deadline-");
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getRemoveOnCancelPolicy()).isTrue();
            assertThat(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
            assertThat(executor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(executor.isShutdown()).isFalse();
        });

        assertThat(schedulerReference.get().getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }
}
