package com.tongji.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return buildExecutor(10, 50, 200, 30, "task-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "publishExecutor")
    public TaskExecutor publishExecutor() {
        return buildExecutor(8, 16, 100, 60, "publish-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "relationEventExecutor")
    public TaskExecutor relationEventExecutor() {
        return buildExecutor(4, 8, 200, 60, "relation-event-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "canalOutboxExecutor")
    public TaskExecutor canalOutboxExecutor() {
        return buildExecutor(2, 4, 50, 60, "canal-outbox-", new ThreadPoolExecutor.AbortPolicy(), 60);
    }

    @Bean(name = "reconciliationExecutor")
    public TaskExecutor reconciliationExecutor() {
        return buildExecutor(2, 4, 100, 60, "reconciliation-", new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "commentReadExecutor")
    public TaskExecutor commentReadExecutor(
            @Value("${comment.executor.read.core-size:8}") int coreSize,
            @Value("${comment.executor.read.max-size:16}") int maxSize,
            @Value("${comment.executor.read.queue-capacity:200}") int queueCapacity) {
        return buildExecutor(coreSize, maxSize, queueCapacity, 60, "comment-read-",
                new ThreadPoolExecutor.CallerRunsPolicy(), 60);
    }

    @Bean(name = "commentOutboxExecutor")
    public TaskExecutor commentOutboxExecutor(
            @Value("${comment.executor.outbox.core-size:2}") int coreSize,
            @Value("${comment.executor.outbox.max-size:4}") int maxSize,
            @Value("${comment.executor.outbox.queue-capacity:50}") int queueCapacity) {
        return buildExecutor(coreSize, maxSize, queueCapacity, 60, "comment-outbox-",
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
