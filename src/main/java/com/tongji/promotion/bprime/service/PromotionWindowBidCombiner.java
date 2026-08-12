package com.tongji.promotion.bprime.service;

import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchItemResult;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchResult;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandBatch;
import com.tongji.promotion.bprime.redis.PromotionBidAdmissionState;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 每窗口 flat combiner：以窗口为调度单位，自然聚批并保证所有内存状态有界。
 *
 * <p>同窗口最多一个 drainer；每轮只执行一个批次后重新调度，避免热点窗口饿死其他窗口。</p>
 *
 * @since 2026-08-12
 */
public class PromotionWindowBidCombiner {
    private static final Comparator<PendingBid> BATCH_ORDER = Comparator
            .comparingLong((PendingBid pending) -> pending.command().bidAmount()).reversed()
            .thenComparingLong(PendingBid::ingressSequence);

    private final PromotionRedisDecisionAdapter decisionAdapter;
    private final TaskExecutor drainerExecutor;
    private final PromotionBPrimeProperties properties;
    private final com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics metrics;
    private final PromotionBidAdmissionState admissionState;
    private final Function<com.tongji.promotion.bprime.model.PromotionAuctionDecision,
            SubmitPromotionBidCommandResponse> responseMapper;
    private final Function<PromotionAuctionCommand, SubmitPromotionBidCommandResponse> unavailableMapper;
    private final ConcurrentHashMap<Long, WindowState> windows = new ConcurrentHashMap<>();
    private final AtomicInteger globalPending = new AtomicInteger();
    private final AtomicLong ingressSequence = new AtomicLong();
    public PromotionWindowBidCombiner(
            PromotionRedisDecisionAdapter decisionAdapter,
            TaskExecutor drainerExecutor,
            PromotionBPrimeProperties properties,
            com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics metrics,
            PromotionBidAdmissionState admissionState,
            Function<com.tongji.promotion.bprime.model.PromotionAuctionDecision,
                    SubmitPromotionBidCommandResponse> responseMapper,
            Function<PromotionAuctionCommand, SubmitPromotionBidCommandResponse> unavailableMapper) {
        this.decisionAdapter = decisionAdapter;
        this.drainerExecutor = drainerExecutor;
        this.properties = properties;
        this.metrics = metrics;
        this.admissionState = admissionState;
        this.responseMapper = responseMapper;
        this.unavailableMapper = unavailableMapper;
    }

    /**
     * 提交一个已完成路由与身份校验的命令。
     *
     * @param command 权威裁决命令
     * @return 恰好完成一次的最终响应 Future
     */
    public CompletableFuture<SubmitPromotionBidCommandResponse> submit(PromotionAuctionCommand command) {
        WindowState state = windows.compute(command.auctionWindowId(), (windowId, existing) -> {
            if (existing != null) {
                return existing;
            }
            if (windows.size() >= properties.getBidCombinerMaximumWindows()) {
                return null;
            }
            return new WindowState();
        });
        if (state == null || !reserveGlobalPending()) {
            metrics.recordBidBackpressure();
            return CompletableFuture.completedFuture(unavailableMapper.apply(command));
        }
        CompletableFuture<SubmitPromotionBidCommandResponse> future = new CompletableFuture<>();
        PendingBid pending = new PendingBid(command, ingressSequence.incrementAndGet(), future);
        boolean admitted;
        synchronized (state) {
            admitted = state.pending.size() < properties.getBidWindowPendingCapacity();
            if (admitted) {
                state.pending.add(pending);
                metrics.updateBidCombinerState(globalPending.get(), windows.size());
            }
        }
        if (!admitted) {
            globalPending.decrementAndGet();
            metrics.recordBidBackpressure();
            metrics.updateBidCombinerState(globalPending.get(), windows.size());
            future.complete(unavailableMapper.apply(command));
            return future;
        }
        schedule(command.auctionWindowId(), state);
        return future;
    }

    private boolean reserveGlobalPending() {
        int value = globalPending.incrementAndGet();
        if (value <= properties.getBidGlobalPendingCapacity()) {
            return true;
        }
        globalPending.decrementAndGet();
        return false;
    }

    private void schedule(long windowId, WindowState state) {
        if (!state.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            drainerExecutor.execute(() -> drainOneBatch(windowId, state));
        } catch (RejectedExecutionException exception) {
            state.draining.set(false);
            failAll(windowId, state);
        } catch (RuntimeException exception) {
            state.draining.set(false);
            failAll(windowId, state);
        }
    }

    private void drainOneBatch(long windowId, WindowState state) {
        List<PendingBid> batch = takeBatch(state);
        if (!batch.isEmpty()) {
            decide(windowId, batch);
        }
        state.draining.set(false);
        boolean hasPending;
        synchronized (state) {
            hasPending = !state.pending.isEmpty();
        }
        if (hasPending) {
            schedule(windowId, state);
        } else {
            windows.remove(windowId, state);
            // remove 与新提交交错时，新提交仍持有旧 state；二次检查并重新挂回，避免 lost wakeup。
            synchronized (state) {
                if (!state.pending.isEmpty()) {
                    windows.putIfAbsent(windowId, state);
                    schedule(windowId, state);
                }
            }
        }
    }

    private List<PendingBid> takeBatch(WindowState state) {
        List<PendingBid> candidates;
        synchronized (state) {
            if (state.pending.isEmpty()) {
                return List.of();
            }
            candidates = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        candidates.sort(BATCH_ORDER);
        List<PendingBid> batch = new ArrayList<>(Math.min(
                candidates.size(), properties.getBidDecisionBatchMaximumSize()));
        int argumentBytes = 0;
        int cursor = 0;
        while (cursor < candidates.size() && batch.size() < properties.getBidDecisionBatchMaximumSize()) {
            PendingBid pending = candidates.get(cursor);
            int itemBytes = argumentBytes(pending.command());
            if (!batch.isEmpty() && argumentBytes + itemBytes > properties.getBidDecisionBatchMaximumArgumentBytes()) {
                break;
            }
            batch.add(pending);
            argumentBytes += itemBytes;
            cursor++;
        }
        if (cursor < candidates.size()) {
            synchronized (state) {
                state.pending.addAll(candidates.subList(cursor, candidates.size()));
            }
        }
        return batch;
    }

    private int argumentBytes(PromotionAuctionCommand command) {
        return 64 + utf8Length(command.commandId()) + utf8Length(command.requestHash())
                + utf8Length(command.resourceType()) + utf8Length(command.submittedAt().toString());
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void decide(long windowId, List<PendingBid> pendingBatch) {
        long startedAtNanos = System.nanoTime();
        try {
            List<PromotionAuctionCommand> commands = pendingBatch.stream().map(PendingBid::command).toList();
            PromotionAuctionBatchResult result = decisionAdapter.decide(
                    new PromotionAuctionCommandBatch(windowId, commands));
            admissionState.update(windowId, result);
            Map<Integer, PromotionAuctionBatchItemResult> byInputIndex = new HashMap<>(result.items().size());
            for (PromotionAuctionBatchItemResult item : result.items()) {
                byInputIndex.put(item.inputIndex(), item);
            }
            for (int inputIndex = 0; inputIndex < pendingBatch.size(); inputIndex++) {
                PendingBid pending = pendingBatch.get(inputIndex);
                PromotionAuctionBatchItemResult item = byInputIndex.get(inputIndex);
                if (item == null) {
                    pending.future().complete(unavailableMapper.apply(pending.command()));
                } else {
                    pending.future().complete(responseMapper.apply(item.decision()));
                }
            }
        } catch (RuntimeException exception) {
            for (PendingBid pending : pendingBatch) {
                pending.future().complete(unavailableMapper.apply(pending.command()));
            }
        } finally {
            metrics.recordBidBatch(pendingBatch.size(), System.nanoTime() - startedAtNanos);
            globalPending.addAndGet(-pendingBatch.size());
            metrics.updateBidCombinerState(globalPending.get(), windows.size());
        }
    }

    private void failAll(long windowId, WindowState state) {
        List<PendingBid> pending;
        synchronized (state) {
            pending = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        windows.remove(windowId, state);
        globalPending.addAndGet(-pending.size());
        metrics.updateBidCombinerState(globalPending.get(), windows.size());
        for (PendingBid item : pending) {
            item.future().complete(unavailableMapper.apply(item.command()));
        }
    }

    private static final class WindowState {
        private final AtomicBoolean draining = new AtomicBoolean();
        private final List<PendingBid> pending = new ArrayList<>();
    }

    private record PendingBid(
            PromotionAuctionCommand command,
            long ingressSequence,
            CompletableFuture<SubmitPromotionBidCommandResponse> future
    ) {
    }
}
