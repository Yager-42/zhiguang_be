package com.tongji.reconciliation.executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tongji.reconciliation.scan.ReconciliationScanService;

@Component
public class ReconciliationScheduler {

    private final ReconciliationTaskExecutor executor;
    private final ReconciliationScanService scanService;
    private final TaskExecutor reconciliationExecutor;

    public ReconciliationScheduler(ReconciliationTaskExecutor executor,
                                   ReconciliationScanService scanService,
                                   @Qualifier("reconciliationExecutor") TaskExecutor reconciliationExecutor) {
        this.executor = executor;
        this.scanService = scanService;
        this.reconciliationExecutor = reconciliationExecutor;
    }

    @Scheduled(fixedDelay = 30000L)
    public void executePending() {
        reconciliationExecutor.execute(executor::executePending);
    }

    @Scheduled(fixedDelay = 60000L)
    public void resetStuckRunning() {
        reconciliationExecutor.execute(executor::resetStuckRunning);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 30000L)
    public void scanPostEs() {
        reconciliationExecutor.execute(scanService::scanPostEsBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 60000L)
    public void scanPostGorse() {
        reconciliationExecutor.execute(scanService::scanPostGorseBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 75000L)
    public void scanPostCassandra() {
        reconciliationExecutor.execute(scanService::scanPostCassandraBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 90000L)
    public void scanCommentCassandra() {
        reconciliationExecutor.execute(scanService::scanCommentCassandraBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 105000L)
    public void scanPostCommentCount() {
        reconciliationExecutor.execute(scanService::scanPostCommentCountBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 120000L)
    public void scanCommentReplyCount() {
        reconciliationExecutor.execute(scanService::scanCommentReplyCountBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 135000L)
    public void scanUserFollowGraph() {
        reconciliationExecutor.execute(scanService::scanUserFollowGraphBatch);
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 150000L)
    public void scanPromotionSettledWindow() {
        reconciliationExecutor.execute(scanService::scanPromotionSettledWindowBatch);
    }

    @Scheduled(initialDelay = 10000L, fixedDelay = Long.MAX_VALUE)
    public void recoverStuckPublishingOnStartup() {
        reconciliationExecutor.execute(scanService::recoverStuckPublishingPosts);
    }
}
