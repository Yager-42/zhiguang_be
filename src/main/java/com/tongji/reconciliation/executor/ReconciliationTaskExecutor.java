package com.tongji.reconciliation.executor;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationErrorLog;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReconciliationTaskExecutor {

    static final int BATCH_SIZE = 50;
    static final int MAX_RETRY_COUNT = 5;
    static final int STUCK_RUNNING_MINUTES = 10;

    private final ReconciliationTaskMapper taskMapper;
    private final ReconciliationErrorLogMapper errorLogMapper;
    private final RedissonClient redissonClient;
    private final IdService idService;
    private final Map<String, Reconciler> reconcilers;
    private final Clock clock;

    @Autowired
    public ReconciliationTaskExecutor(ReconciliationTaskMapper taskMapper,
                                      ReconciliationErrorLogMapper errorLogMapper,
                                      RedissonClient redissonClient,
                                      IdService idService,
                                      List<Reconciler> reconcilers) {
        this(taskMapper, errorLogMapper, redissonClient, idService, reconcilers, Clock.systemDefaultZone());
    }

    public ReconciliationTaskExecutor(ReconciliationTaskMapper taskMapper,
                                      ReconciliationErrorLogMapper errorLogMapper,
                                      RedissonClient redissonClient,
                                      IdService idService,
                                      List<Reconciler> reconcilers,
                                      Clock clock) {
        this.taskMapper = taskMapper;
        this.errorLogMapper = errorLogMapper;
        this.redissonClient = redissonClient;
        this.idService = idService;
        this.reconcilers = reconcilers.stream()
                .collect(Collectors.toMap(Reconciler::taskType, Function.identity()));
        this.clock = clock;
    }

    public void executePending() {
        for (ReconciliationTask task : taskMapper.pollPending(BATCH_SIZE)) {
            executeWithLock(task);
        }
    }

    void executeOne(ReconciliationTask task) {
        Instant startedAt = Instant.now(clock);
        try {
            Reconciler reconciler = reconcilers.get(task.getTaskType());
            if (reconciler == null) {
                throw new IllegalStateException("No reconciler registered for task type " + task.getTaskType());
            }
            reconciler.reconcile(task);
            taskMapper.markSucceeded(task.getId(), elapsedMillis(startedAt));
        } catch (Exception e) {
            handleFailure(task, e, elapsedMillis(startedAt));
        }
    }

    public void resetStuckRunning() {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minusMinutes(STUCK_RUNNING_MINUTES);
        for (ReconciliationTask task : taskMapper.findStuckRunning(staleBefore, BATCH_SIZE)) {
            taskMapper.resetRunningToPending(task.getId());
        }
    }

    private void executeWithLock(ReconciliationTask task) {
        RLock lock = redissonClient.getLock("recon:lock:" + task.getId());
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (taskMapper.markRunning(task.getId()) == 1) {
                executeOne(task);
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleFailure(ReconciliationTask task, Exception e, long executionDurationMs) {
        int oldRetryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (oldRetryCount >= MAX_RETRY_COUNT) {
            taskMapper.markDead(task.getId(), executionDurationMs, message);
            errorLogMapper.insert(ReconciliationErrorLog.builder()
                    .id(idService.nextId(IdNamespace.RECONCILIATION_TASK))
                    .taskId(task.getId())
                    .executionDurationMs(executionDurationMs)
                    .errorMessage(message)
                    .stackTrace(stackTrace(e))
                    .createdAt(LocalDateTime.now(clock))
                    .build());
            return;
        }

        long delayMinutes = 1L << oldRetryCount;
        taskMapper.markPendingRetry(
                task.getId(),
                oldRetryCount + 1,
                LocalDateTime.now(clock).plusMinutes(delayMinutes),
                executionDurationMs,
                message
        );
    }

    private long elapsedMillis(Instant startedAt) {
        return Duration.between(startedAt, Instant.now(clock)).toMillis();
    }

    private static String stackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
