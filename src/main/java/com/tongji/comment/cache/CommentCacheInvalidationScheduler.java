package com.tongji.comment.cache;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.CustomizableThreadCreator;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 提供评论缓存失效专用的延迟调度，且不参与 Spring 全局定时任务调度器选择。
 */
@Component
public class CommentCacheInvalidationScheduler {
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 60;

    private final ScheduledThreadPoolExecutor executor;

    public CommentCacheInvalidationScheduler() {
        CustomizableThreadCreator threadCreator = new CustomizableThreadCreator("comment-cache-invalidation-");
        this.executor = new ScheduledThreadPoolExecutor(1, threadCreator::createThread);
        this.executor.setRemoveOnCancelPolicy(true);
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void schedule(Runnable task, long delayMillis) {
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
