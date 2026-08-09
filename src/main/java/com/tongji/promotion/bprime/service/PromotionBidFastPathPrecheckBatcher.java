package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.redis.PromotionBidFastPathPrecheckRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Coalesces WebSocket fast-path guard reads into bounded Redis pipelines.
 *
 * <p>Each candidate still receives its own command-dedupe result. Only transport round trips and repeated window
 * status reads are aggregated; the safety order remains identical to the authoritative Lua guards.</p>
 */
@Component
public class PromotionBidFastPathPrecheckBatcher {

    private final PromotionBidFastPathPrecheckRepository repository;
    private final int batchSize;
    private final int workerCount;
    private final long maxWaitNanos;
    private final boolean enabled;
    private final ArrayBlockingQueue<PendingCheck> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService workers;

    public PromotionBidFastPathPrecheckBatcher(PromotionBidFastPathPrecheckRepository repository,
                                               PromotionBPrimeProperties properties) {
        this.repository = repository;
        this.batchSize = properties.getFastRejectPrecheckBatchSize();
        this.workerCount = properties.getFastRejectPrecheckWorkerCount();
        this.maxWaitNanos = TimeUnit.MICROSECONDS.toNanos(properties.getFastRejectPrecheckMaxWaitMicros());
        this.enabled = properties.isEnabled() && properties.isFastRejectEnabled();
        this.queue = new ArrayBlockingQueue<>(properties.getFastRejectPrecheckQueueCapacity());
    }

    @PostConstruct
    void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        workers = Executors.newFixedThreadPool(workerCount,
                Thread.ofPlatform().name("promotion-fast-precheck-", 0).factory());
        for (int index = 0; index < workerCount; index++) {
            workers.execute(this::runWorker);
        }
    }

    public CompletableFuture<PromotionBidFastPathPrecheckRepository.Result> checkAsync(
            long auctionWindowId,
            String commandId) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(PromotionBidFastPathPrecheckRepository.Result.unavailable());
        }
        CompletableFuture<PromotionBidFastPathPrecheckRepository.Result> result = new CompletableFuture<>();
        if (!queue.offer(new PendingCheck(auctionWindowId, commandId, result))) {
            result.complete(PromotionBidFastPathPrecheckRepository.Result.unavailable());
        }
        return result;
    }

    private void runWorker() {
        List<PendingCheck> batch = new ArrayList<>(batchSize);
        while (running.get() || !queue.isEmpty()) {
            try {
                PendingCheck first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                if (maxWaitNanos > 0L && queue.size() < batchSize - 1) {
                    LockSupport.parkNanos(maxWaitNanos);
                }
                queue.drainTo(batch, batchSize - 1);
                completeBatch(batch);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                batch.clear();
            }
        }
    }

    private void completeBatch(List<PendingCheck> batch) {
        try {
            List<PromotionBidFastPathPrecheckRepository.Check> checks = batch.stream()
                    .map(item -> new PromotionBidFastPathPrecheckRepository.Check(
                            item.auctionWindowId(), item.commandId()))
                    .toList();
            List<PromotionBidFastPathPrecheckRepository.Result> results = repository.checkBatch(checks);
            if (results.size() != batch.size()) {
                completeUnavailable(batch);
                return;
            }
            for (int index = 0; index < batch.size(); index++) {
                batch.get(index).result().complete(results.get(index));
            }
        } catch (RuntimeException exception) {
            completeUnavailable(batch);
        }
    }

    private void completeUnavailable(List<PendingCheck> batch) {
        for (PendingCheck pending : batch) {
            pending.result().complete(PromotionBidFastPathPrecheckRepository.Result.unavailable());
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        PendingCheck pending;
        while ((pending = queue.poll()) != null) {
            pending.result().complete(PromotionBidFastPathPrecheckRepository.Result.unavailable());
        }
    }

    private record PendingCheck(
            long auctionWindowId,
            String commandId,
            CompletableFuture<PromotionBidFastPathPrecheckRepository.Result> result) {
    }
}
