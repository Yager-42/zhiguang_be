package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reliably publishes committed promotion commands without blocking the HTTP request thread.
 *
 * <p>The command table is the durable handoff. Broker sends remain individually ordered, while claim and status writes
 * are batched to avoid one MySQL transaction per command. A crash after broker acceptance may duplicate a command;
 * Redis command idempotency absorbs that delivery.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "promotion.bprime", name = "legacy-command-recovery-enabled",
        havingValue = "true")
public class PromotionCommandDispatchService {

    private final PromotionAuctionCommandMapper commandMapper;
    private final PromotionCommandMessagePort messagePort;
    private final PromotionBPrimeProperties properties;
    private final TaskExecutor executor;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final Set<String> scheduledCommandIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<PromotionAuctionCommandRecord> pendingCommands = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger queuedCommands = new AtomicInteger();

    public PromotionCommandDispatchService(PromotionAuctionCommandMapper commandMapper,
                                           PromotionCommandMessagePort messagePort,
                                           PromotionBPrimeProperties properties,
                                           @Qualifier("promotionCommandExecutor") TaskExecutor executor,
                                           PromotionPerformanceMetrics performanceMetrics) {
        this.commandMapper = commandMapper;
        this.messagePort = messagePort;
        this.properties = properties;
        this.executor = executor;
        this.performanceMetrics = performanceMetrics;
    }

    /** Schedules a command only after its insert transaction has committed. */
    public void dispatchAfterCommit(PromotionAuctionCommandRecord command) {
        if (!properties.isEnabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    performanceMetrics.recordIngressAccepted();
                    enqueue(command);
                }
            });
            return;
        }
        performanceMetrics.recordIngressAccepted();
        enqueue(command);
    }

    /** Recovers committed commands that were not queued or whose publisher worker stopped mid-send. */
    @Scheduled(fixedDelayString = "${promotion.bprime.command-publish-scan-delay-ms:1000}")
    public void recoverPublishableCommands() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant publishStaleBefore = publishStaleBefore();
        Instant decisionStaleBefore = decisionStaleBefore();
        List<PromotionAuctionCommandRecord> candidates = commandMapper.listPublishable(
                        publishStaleBefore, decisionStaleBefore, properties.getCommandPublishBatchSize()).stream()
                .filter(command -> scheduledCommandIds.add(command.getCommandId()))
                .toList();
        if (candidates.isEmpty()) {
            startWorkers();
            return;
        }
        List<String> commandIds = candidates.stream().map(PromotionAuctionCommandRecord::getCommandId).toList();
        try {
            if (commandMapper.claimForPublishingBatch(
                    commandIds, publishStaleBefore, decisionStaleBefore) == 0) {
                scheduledCommandIds.removeAll(commandIds);
                return;
            }
            pendingCommands.addAll(candidates);
            queuedCommands.addAndGet(candidates.size());
            updateQueueMetrics();
            startWorkers();
        } catch (RuntimeException exception) {
            scheduledCommandIds.removeAll(commandIds);
            throw exception;
        }
    }

    private void enqueue(PromotionAuctionCommandRecord command) {
        if (!scheduledCommandIds.add(command.getCommandId())) {
            return;
        }
        pendingCommands.add(command);
        queuedCommands.incrementAndGet();
        updateQueueMetrics();
        startWorkers();
    }

    private void startWorkers() {
        int workerLimit = properties.getCommandPublisherWorkerCount();
        while (!pendingCommands.isEmpty()) {
            int currentWorkers = activeWorkers.get();
            if (currentWorkers >= workerLimit) {
                return;
            }
            if (activeWorkers.compareAndSet(currentWorkers, currentWorkers + 1)) {
                updateQueueMetrics();
                if (!submitWorker()) {
                    return;
                }
            }
        }
    }

    private boolean submitWorker() {
        try {
            executor.execute(this::publishBatch);
            return true;
        } catch (TaskRejectedException exception) {
            activeWorkers.decrementAndGet();
            updateQueueMetrics();
            log.debug("Promotion command publisher executor rejected a batch; recovery will retry it");
            return false;
        }
    }

    private void publishBatch() {
        List<PromotionAuctionCommandRecord> batch = drainBatch();
        List<String> publishedCommandIds = new ArrayList<>(batch.size());
        try {
            for (PromotionAuctionCommandRecord command : batch) {
                try {
                    messagePort.send(command.toCommand());
                    publishedCommandIds.add(command.getCommandId());
                } catch (RuntimeException exception) {
                    log.error("Failed to publish promotion command commandId={}", command.getCommandId(), exception);
                }
            }
            if (!publishedCommandIds.isEmpty()) {
                commandMapper.markPublishedBatch(publishedCommandIds);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to persist promotion command publish batch size={}", publishedCommandIds.size(), exception);
        } finally {
            batch.forEach(command -> scheduledCommandIds.remove(command.getCommandId()));
            activeWorkers.decrementAndGet();
            updateQueueMetrics();
            startWorkers();
        }
    }

    private List<PromotionAuctionCommandRecord> drainBatch() {
        List<PromotionAuctionCommandRecord> batch = new ArrayList<>(properties.getCommandPublishBatchSize());
        PromotionAuctionCommandRecord command;
        while (batch.size() < properties.getCommandPublishBatchSize()
                && (command = pendingCommands.poll()) != null) {
            batch.add(command);
            queuedCommands.decrementAndGet();
        }
        updateQueueMetrics();
        return batch;
    }

    private void updateQueueMetrics() {
        performanceMetrics.updatePublisherState(queuedCommands.get(), activeWorkers.get());
    }

    private Instant publishStaleBefore() {
        return Instant.now().minusMillis(properties.getCommandPublishClaimTimeoutMs());
    }

    private Instant decisionStaleBefore() {
        return Instant.now().minusMillis(properties.getCommandDecisionTimeoutMs());
    }
}
