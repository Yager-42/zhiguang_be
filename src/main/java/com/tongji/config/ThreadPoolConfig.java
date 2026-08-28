package com.tongji.config;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return buildExecutor(10, 50, 200, 30, "task-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }



    @Bean(name = "canalOutboxExecutor")
    public TaskExecutor canalOutboxExecutor() {
        return buildExecutor(2, 4, 50, 60, "canal-outbox-", new ThreadPoolExecutor.AbortPolicy(), 60);
    }

    @Bean(name = "reconciliationExecutor")
    public TaskExecutor reconciliationExecutor() {
        return buildExecutor(2, 4, 100, 60, "reconciliation-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "promotionBidDrainerExecutor")
    public TaskExecutor promotionBidDrainerExecutor(PromotionBPrimeProperties properties) {
        int threadCount = properties.getBidDrainerThreadCount();
        return buildExecutor(threadCount, threadCount, properties.getBidReadyWindowQueueCapacity(), 60,
                "promotion-bid-drainer-", new ThreadPoolExecutor.AbortPolicy(), 60);
    }

    @Bean(name = "promotionBidWebSocketOutboundExecutor")
    public TaskExecutor promotionBidWebSocketOutboundExecutor(
            @Value("${promotion.bprime.web-socket-outbound-thread-count:8}") int threadCount,
            @Value("${promotion.bprime.web-socket-channel-queue-capacity:65536}") int queueCapacity) {
        return buildExecutor(threadCount, threadCount, queueCapacity, 60, "promotion-bid-ws-outbound-",
                new ThreadPoolExecutor.AbortPolicy(), 60);
    }

    /**
     * 为可恢复的房间公共状态通知提供独立调度资源，避免占用最终 ACK 写线程。
     *
     * @param threadCount 固定调度线程数，必须为正数
     * @return 已初始化且由 Spring 关闭的调度器
     */
    @Bean(name = "promotionPublicUpdateScheduler")
    public ThreadPoolTaskScheduler promotionPublicUpdateScheduler(
            @Value("${promotion.bprime.public-update-scheduler-thread-count:2}") int threadCount) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threadCount);
        scheduler.setThreadNamePrefix("promotion-public-update-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "commentReadExecutor")
    public TaskExecutor commentReadExecutor(
            @Value("${comment.executor.read.core-size:8}") int coreSize,
            @Value("${comment.executor.read.max-size:16}") int maxSize,
            @Value("${comment.executor.read.queue-capacity:200}") int queueCapacity) {
        return buildExecutor(coreSize, maxSize, queueCapacity, 60, "comment-read-",
                new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    private ThreadPoolTaskExecutor buildExecutor(int corePoolSize,
                                                 int maxPoolSize,
                                                 int queueCapacity,
                                                 int keepAliveSeconds,
                                                 String threadNamePrefix,
                                                 RejectedExecutionHandler rejectionHandler,
                                                 int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(rejectionHandler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
