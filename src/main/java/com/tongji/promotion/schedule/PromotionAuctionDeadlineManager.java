package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.bprime.redis.PromotionAuctionUnavailableException;
import com.tongji.promotion.bprime.redis.PromotionRedisCloseOutcome;
import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.LongSupplier;

/**
 * 管理 JVM 内每个 OPEN 竞价窗口唯一的固定截止任务。
 *
 * <p>本组件只负责按 Redis TIME 的相对时间触发 {@link PromotionRedisWindowCloser}，不裁决终态、
 * 不写 MySQL，也不延长 deadline。实例状态受同一互斥锁保护，可安全地被并发调用。</p>
 *
 * @since 2026-09-03
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionDeadlineManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionAuctionDeadlineManager.class);
    private static final long INITIAL_RETRY_DELAY_MILLIS = 1_000L;
    private static final long MAXIMUM_RETRY_DELAY_MILLIS = 30_000L;
    private static final int MAXIMUM_RETRY_SHIFT = 5;

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionRedisWindowCloser windowCloser;
    private final PromotionAuctionAvailabilityGate availabilityGate;
    private final ThreadPoolTaskScheduler scheduler;
    private final LongSupplier redisTimeMillis;
    private final Object taskLock = new Object();
    private final Map<Long, DeadlineTask> tasksByWindowId = new HashMap<>();

    private boolean acceptingTasks = true;

    /**
     * 创建使用 Redis TIME 和专用 Spring 调度器的 Deadline Manager。
     *
     * @param windowMapper OPEN 窗口查询入口
     * @param windowCloser Redis Lua 关窗入口
     * @param scheduler 专用截止时间调度器
     * @param redisTemplate Redis TIME 访问入口
     */
    @Autowired
    public PromotionAuctionDeadlineManager(
            PromotionAuctionWindowMapper windowMapper,
            PromotionRedisWindowCloser windowCloser,
            PromotionAuctionAvailabilityGate availabilityGate,
            @Qualifier("promotionAuctionDeadlineScheduler") ThreadPoolTaskScheduler scheduler,
            StringRedisTemplate redisTemplate) {
        this(windowMapper, windowCloser, availabilityGate, scheduler, () -> readRedisTimeMillis(redisTemplate));
    }

    PromotionAuctionDeadlineManager(PromotionAuctionWindowMapper windowMapper,
                                    PromotionRedisWindowCloser windowCloser,
                                    PromotionAuctionAvailabilityGate availabilityGate,
                                    ThreadPoolTaskScheduler scheduler,
                                    LongSupplier redisTimeMillis) {
        this.windowMapper = Objects.requireNonNull(windowMapper, "windowMapper must not be null");
        this.windowCloser = Objects.requireNonNull(windowCloser, "windowCloser must not be null");
        this.availabilityGate = Objects.requireNonNull(availabilityGate, "availabilityGate must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.redisTimeMillis = Objects.requireNonNull(redisTimeMillis, "redisTimeMillis must not be null");
    }

    /**
     * 按窗口固定 deadline 幂等注册唯一截止任务。
     *
     * <p>初始延迟由 Redis TIME 计算。相同窗口与相同 deadline 已有任务时不重复注册；注册失败时
     * 不发布新的本地任务，并将异常抛给调用方实施 fail-closed。</p>
     *
     * @param window 待注册的 OPEN 竞价窗口，ID 必须为正且结束时间不能为空
     * @throws IllegalArgumentException 当窗口不满足注册前置条件时
     * @throws IllegalStateException 当同一窗口尝试注册不同 deadline 时
     * @throws PromotionAuctionUnavailableException 当 Redis TIME 不可用时
     * @throws RejectedExecutionException 当专用调度器拒绝任务时
     */
    public void schedule(PromotionAuctionWindow window) {
        requireSchedulableWindow(window);
        long windowId = window.getId();
        long deadlineEpochMs = window.getWindowEndAt().toEpochMilli();

        synchronized (taskLock) {
            requireAcceptingTasks();
            if (hasCurrentTask(windowId, deadlineEpochMs)) {
                return;
            }
        }

        long redisNowEpochMs = redisTimeMillis.getAsLong();
        long delayMillis = remainingDelayMillis(redisNowEpochMs, deadlineEpochMs);

        synchronized (taskLock) {
            requireAcceptingTasks();
            if (hasCurrentTask(windowId, deadlineEpochMs)) {
                return;
            }
            try {
                installTask(windowId, deadlineEpochMs, delayMillis);
            } catch (RuntimeException exception) {
                availabilityGate.pause();
                throw exception;
            }
        }
    }

    /**
     * 取消窗口的本地截止任务；窗口没有任务时幂等返回。
     *
     * @param auctionWindowId 竞价窗口 ID
     */
    public void cancel(long auctionWindowId) {
        synchronized (taskLock) {
            DeadlineTask task = tasksByWindowId.remove(auctionWindowId);
            if (task != null) {
                task.future.cancel(false);
            }
        }
    }

    /**
     * 从 MySQL 一次性读取全部 OPEN 窗口并幂等恢复截止任务。
     *
     * <p>恢复只注册 timer；终态由 timer 触发的 close Lua 幂等裁决，方法不直接修改 MySQL。
     * 任一读取或注册失败均继续抛出，使启动流程实施 fail-closed。</p>
     */
    public void recoverOpenWindows() {
        List<PromotionAuctionWindow> openWindows = Objects.requireNonNull(
                windowMapper.listActiveWindows(), "listActiveWindows must not return null");
        for (PromotionAuctionWindow window : openWindows) {
            schedule(window);
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (taskLock) {
            acceptingTasks = false;
            for (DeadlineTask task : tasksByWindowId.values()) {
                task.future.cancel(false);
            }
            tasksByWindowId.clear();
        }
    }

    private void installTask(long windowId, long deadlineEpochMs, long delayMillis) {
        DeadlineTask previous = tasksByWindowId.get(windowId);
        DeadlineTask task = new DeadlineTask(windowId, deadlineEpochMs);
        task.future = submit(task, delayMillis);
        tasksByWindowId.put(windowId, task);
        if (previous != null) {
            previous.future.cancel(false);
        }
    }

    private ScheduledFuture<?> submit(DeadlineTask task, long delayMillis) {
        Instant triggerAt = scheduler.getClock().instant().plusMillis(delayMillis);
        ScheduledFuture<?> future = scheduler.schedule(() -> execute(task), triggerAt);
        if (future == null) {
            throw new RejectedExecutionException("Promotion auction deadline scheduler rejected task");
        }
        return future;
    }

    private void execute(DeadlineTask task) {
        PromotionRedisCloseOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    windowCloser.close(task.windowId), "PromotionRedisWindowCloser returned null");
        } catch (RuntimeException exception) {
            retryAfterFailure(task, exception);
            return;
        }

        if (outcome instanceof PromotionRedisCloseOutcome.Closed
                || outcome instanceof PromotionRedisCloseOutcome.AlreadyTerminal) {
            removeIfCurrent(task);
            return;
        }
        if (outcome instanceof PromotionRedisCloseOutcome.NotDue notDue) {
            task.consecutiveFailures = 0;
            long delayMillis;
            try {
                delayMillis = positiveRemainingDelayMillis(
                        notDue.redisNowEpochMs(), notDue.deadlineEpochMs());
            } catch (RuntimeException exception) {
                retryAfterFailure(task, exception);
                return;
            }
            reschedule(task, delayMillis);
            return;
        }
        retryAfterFailure(task, new IllegalStateException(
                "Unsupported promotion Redis close outcome: " + outcome.getClass().getName()));
    }

    private void retryAfterFailure(DeadlineTask task, RuntimeException failure) {
        int failureCount = Math.min(task.consecutiveFailures + 1, MAXIMUM_RETRY_SHIFT + 1);
        task.consecutiveFailures = failureCount;
        long retryDelayMillis = retryDelayMillis(failureCount);
        LOGGER.error("推广竞价 deadline 关窗失败，将保留任务并重试，auctionWindowId={}, retryDelayMillis={}",
                task.windowId, retryDelayMillis, failure);
        reschedule(task, retryDelayMillis);
    }

    private void reschedule(DeadlineTask task, long delayMillis) {
        synchronized (taskLock) {
            if (!acceptingTasks || tasksByWindowId.get(task.windowId) != task) {
                return;
            }
            try {
                task.future = submit(task, delayMillis);
            } catch (RuntimeException exception) {
                tasksByWindowId.remove(task.windowId, task);
                availabilityGate.pause();
                LOGGER.error("推广竞价 deadline 重排失败，auctionWindowId={}", task.windowId, exception);
                throw exception;
            }
        }
    }

    private void removeIfCurrent(DeadlineTask task) {
        synchronized (taskLock) {
            tasksByWindowId.remove(task.windowId, task);
        }
    }

    private boolean hasCurrentTask(long windowId, long deadlineEpochMs) {
        DeadlineTask existing = tasksByWindowId.get(windowId);
        if (existing == null || existing.future.isCancelled() || existing.future.isDone()) {
            return false;
        }
        if (existing.deadlineEpochMs != deadlineEpochMs) {
            throw new IllegalStateException("auction window deadline is immutable");
        }
        return true;
    }

    private void requireAcceptingTasks() {
        if (!acceptingTasks) {
            throw new RejectedExecutionException("Promotion auction deadline manager is shut down");
        }
    }

    private static void requireSchedulableWindow(PromotionAuctionWindow window) {
        if (window == null) {
            throw new IllegalArgumentException("window must not be null");
        }
        if (window.getId() <= 0) {
            throw new IllegalArgumentException("window id must be positive");
        }
        if (window.getWindowEndAt() == null) {
            throw new IllegalArgumentException("window end time must not be null");
        }
        if (window.getStatus() != PromotionAuctionWindowStatus.OPEN) {
            throw new IllegalArgumentException("only OPEN window can be scheduled");
        }
    }

    private static long readRedisTimeMillis(StringRedisTemplate redisTemplate) {
        try {
            Long redisNowEpochMs = redisTemplate.execute(
                    (RedisCallback<Long>) connection -> connection.serverCommands().time());
            if (redisNowEpochMs == null) {
                throw new PromotionAuctionUnavailableException("promotion auction Redis TIME returned null");
            }
            return redisNowEpochMs;
        } catch (PromotionAuctionUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("promotion auction Redis TIME failed", exception);
        }
    }

    private static long remainingDelayMillis(long redisNowEpochMs, long deadlineEpochMs) {
        if (deadlineEpochMs <= redisNowEpochMs) {
            return 0L;
        }
        return Math.subtractExact(deadlineEpochMs, redisNowEpochMs);
    }

    private static long positiveRemainingDelayMillis(long redisNowEpochMs, long deadlineEpochMs) {
        long remainingMillis = Math.subtractExact(deadlineEpochMs, redisNowEpochMs);
        if (remainingMillis <= 0L) {
            throw new IllegalStateException("NOT_DUE outcome must have a future Redis deadline");
        }
        return remainingMillis;
    }

    private static long retryDelayMillis(int failureCount) {
        int shift = Math.min(Math.max(failureCount - 1, 0), MAXIMUM_RETRY_SHIFT);
        return Math.min(INITIAL_RETRY_DELAY_MILLIS << shift, MAXIMUM_RETRY_DELAY_MILLIS);
    }

    private static final class DeadlineTask {
        private final long windowId;
        private final long deadlineEpochMs;
        private ScheduledFuture<?> future;
        private int consecutiveFailures;

        private DeadlineTask(long windowId, long deadlineEpochMs) {
            this.windowId = windowId;
            this.deadlineEpochMs = deadlineEpochMs;
        }
    }
}
