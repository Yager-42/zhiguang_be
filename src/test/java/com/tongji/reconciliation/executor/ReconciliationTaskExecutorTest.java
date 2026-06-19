package com.tongji.reconciliation.executor;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationErrorLog;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationTaskExecutorTest {

    private static final Instant NOW = Instant.parse("2026-06-18T10:15:30Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private ReconciliationTaskMapper taskMapper;
    @Mock
    private ReconciliationErrorLogMapper errorLogMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private IdService idService;
    @Mock
    private Reconciler reconciler;

    private ReconciliationTaskExecutor executor;
    private TestLock lock;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        when(reconciler.taskType()).thenReturn("es_index");
        lock = new TestLock();
        when(redissonClient.getLock("recon:lock:42")).thenReturn(lock.proxy);
        executor = new ReconciliationTaskExecutor(
                taskMapper,
                errorLogMapper,
                redissonClient,
                idService,
                List.of(reconciler),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void pollRunsLockedClaimedTaskAndMarksSucceeded() {
        ReconciliationTask task = task(42L, "es_index", 0);
        when(taskMapper.pollPending(50)).thenReturn(List.of(task));
        lock.locked = true;
        when(taskMapper.markRunning(42L)).thenReturn(1);

        executor.executePending();

        org.mockito.InOrder inOrder = inOrder(taskMapper, reconciler);
        inOrder.verify(taskMapper).markRunning(42L);
        inOrder.verify(reconciler).reconcile(task);
        inOrder.verify(taskMapper).markSucceeded(42L, 0L);
        assertThat(lock.unlocks).isEqualTo(1);
    }

    @Test
    void failureBelowRetryLimitSchedulesNextBackoff() {
        ReconciliationTask task = task(42L, "es_index", 2);
        when(taskMapper.pollPending(50)).thenReturn(List.of(task));
        lock.locked = true;
        when(taskMapper.markRunning(42L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(reconciler).reconcile(task);

        executor.executePending();

        verify(taskMapper).markPendingRetry(
                eq(42L),
                eq(3),
                eq(NOW_LOCAL.plusMinutes(4)),
                eq(0L),
                eq("boom")
        );
        verify(errorLogMapper, never()).insert(any());
    }

    @Test
    void failureAtRetryLimitMarksDeadAndWritesErrorLog() {
        ReconciliationTask task = task(42L, "es_index", 5);
        when(taskMapper.pollPending(50)).thenReturn(List.of(task));
        lock.locked = true;
        when(taskMapper.markRunning(42L)).thenReturn(1);
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(99L);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(reconciler).reconcile(task);

        executor.executePending();

        verify(taskMapper).markDead(42L, 0L, "boom");
        ArgumentCaptor<ReconciliationErrorLog> captor = ArgumentCaptor.forClass(ReconciliationErrorLog.class);
        verify(errorLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L);
        assertThat(captor.getValue().getTaskId()).isEqualTo(42L);
        assertThat(captor.getValue().getExecutionDurationMs()).isZero();
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("boom");
        assertThat(captor.getValue().getStackTrace()).contains("IllegalStateException");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW_LOCAL);
    }

    @Test
    void skipsTaskWhenLockCannotBeAcquired() {
        ReconciliationTask task = task(42L, "es_index", 0);
        when(taskMapper.pollPending(50)).thenReturn(List.of(task));
        lock.locked = false;

        executor.executePending();

        verify(taskMapper, never()).markRunning(42L);
        verify(reconciler, never()).reconcile(any());
        assertThat(lock.unlocks).isZero();
    }

    @Test
    void skipsTaskWhenPendingToRunningCasIsLost() {
        ReconciliationTask task = task(42L, "es_index", 0);
        when(taskMapper.pollPending(50)).thenReturn(List.of(task));
        lock.locked = true;
        when(taskMapper.markRunning(42L)).thenReturn(0);

        executor.executePending();

        verify(reconciler, never()).reconcile(any());
        verify(taskMapper, never()).markSucceeded(eq(42L), any(Long.class));
        assertThat(lock.unlocks).isEqualTo(1);
    }

    @Test
    void retryDelaysAreExactlyOneTwoFourEightAndSixteenMinutes() {
        for (int oldRetryCount = 0; oldRetryCount < 5; oldRetryCount++) {
            ReconciliationTask task = task(42L, "missing_type", oldRetryCount);

            executor.executeOne(task);

            verify(taskMapper).markPendingRetry(
                    42L,
                    oldRetryCount + 1,
                    NOW_LOCAL.plusMinutes(1L << oldRetryCount),
                    0L,
                    "No reconciler registered for task type missing_type"
            );
        }
    }

    @Test
    void resetsStuckRunningTasksOlderThanTenMinutes() {
        LocalDateTime staleBefore = NOW_LOCAL.minusMinutes(10);
        when(taskMapper.findStuckRunning(staleBefore, 50))
                .thenReturn(List.of(task(42L, "es_index", 0)));

        executor.resetStuckRunning();

        verify(taskMapper).findStuckRunning(staleBefore, 50);
        verify(taskMapper).resetRunningToPending(42L);
    }

    private static ReconciliationTask task(Long id, String taskType, int retryCount) {
        return ReconciliationTask.builder()
                .id(id)
                .taskType(taskType)
                .targetType("post")
                .targetId(100L)
                .retryCount(retryCount)
                .build();
    }

    private static class TestLock {
        boolean locked;
        int unlocks;
        final RLock proxy = (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (ignored, method, args) -> {
                    if ("tryLock".equals(method.getName()) && method.getParameterCount() == 0) {
                        return locked;
                    }
                    if ("unlock".equals(method.getName())) {
                        unlocks++;
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "TestLock";
                    }
                    return defaultValue(method.getReturnType());
                });

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            return 0;
        }
    }
}
