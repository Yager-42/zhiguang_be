package com.tongji.comment.cache;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommentCacheInvalidationSchedulerTest {

    @Test
    void usesDedicatedThreadWithoutBecomingSpringTaskScheduler() throws Exception {
        CommentCacheInvalidationScheduler scheduler = new CommentCacheInvalidationScheduler();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        try {
            scheduler.schedule(() -> {
                threadName.set(Thread.currentThread().getName());
                completed.countDown();
            }, 0L);

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("comment-cache-invalidation-");
            assertThat(scheduler).isNotInstanceOf(TaskScheduler.class);
        } finally {
            scheduler.shutdown();
        }
    }
}
