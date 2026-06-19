package com.tongji.reconciliation.executor;

import com.tongji.reconciliation.scan.ReconciliationScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class ReconciliationSchedulerTest {

    private ReconciliationTaskExecutor executor;
    private ReconciliationScanService scanService;
    private TaskExecutor reconciliationExecutor;
    private ReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = mock(ReconciliationTaskExecutor.class);
        scanService = mock(ReconciliationScanService.class);
        reconciliationExecutor = mock(TaskExecutor.class);
        scheduler = new ReconciliationScheduler(executor, scanService, reconciliationExecutor);
    }

    @Test
    void scheduledMethodsDispatchToReconciliationExecutor() {
        scheduler.executePending();
        scheduler.resetStuckRunning();
        scheduler.scanPostEs();
        scheduler.scanPostRag();
        scheduler.scanPostGorse();
        scheduler.scanPostCassandra();
        scheduler.scanCommentCassandra();
        scheduler.scanPostCommentCount();
        scheduler.scanCommentReplyCount();
        scheduler.scanUserFollowGraph();
        scheduler.recoverStuckPublishingOnStartup();

        verify(reconciliationExecutor, times(11)).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }
}
