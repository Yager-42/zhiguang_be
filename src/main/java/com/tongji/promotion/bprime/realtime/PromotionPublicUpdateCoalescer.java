package com.tongji.promotion.bprime.realtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * 按窗口合并高频竞价增量，并使用独立调度资源发布可恢复的公共状态。
 *
 * <p>同一 flush 周期内每个 campaign 只保留最高版本。公共通知可被慢连接覆盖，客户端通过连续
 * {@code eventVersion} 检测缺帧并读取 snapshot；私有 outcome 不经过本组件。</p>
 *
 * @since 2026-08-09
 */
@Component
@ConditionalOnProperty(
        name = {"promotion.bprime.enabled", "promotion.bprime.fanout-consumer-enabled"},
        havingValue = "true")
public class PromotionPublicUpdateCoalescer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionPublicUpdateCoalescer.class);

    private final PromotionAuctionRealtimePublisher publisher;
    private final ThreadPoolTaskScheduler scheduler;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final Duration flushInterval;
    private final Cache<Long, WindowUpdates> windows;
    private volatile ScheduledFuture<?> flushTask;
    private volatile boolean running;

    public PromotionPublicUpdateCoalescer(
            PromotionAuctionRealtimePublisher publisher,
            @Qualifier("promotionPublicUpdateScheduler") ThreadPoolTaskScheduler scheduler,
            PromotionPerformanceMetrics performanceMetrics,
            PromotionBPrimeProperties properties) {
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.performanceMetrics = performanceMetrics;
        this.flushInterval = Duration.ofMillis(properties.getPublicUpdateFlushIntervalMs());
        this.windows = Caffeine.<Long, WindowUpdates>newBuilder()
                .maximumSize(properties.getPublicUpdateMaximumWindows())
                .expireAfterAccess(Duration.ofHours(2))
                .removalListener((Long windowId, WindowUpdates updates, RemovalCause cause) -> {
                    if (updates != null && updates.hasPending()) {
                        LOGGER.warn("Pending promotion public update evicted: windowId={}, cause={}", windowId, cause);
                    }
                })
                .build();
    }

    /**
     * 合并一条已持久化的成功竞价；该方法不等待公共 WebSocket 写出。
     *
     * @param decision 类型为 {@code BID_ACCEPTED} 的权威结果
     */
    public void enqueueBid(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        if (!"BID_ACCEPTED".equals(decision.type())) {
            throw new IllegalArgumentException("public bid delta requires BID_ACCEPTED decision");
        }
        windows.get(decision.auctionWindowId(), ignored -> new WindowUpdates()).merge(decision);
    }

    /**
     * 先发布尚未刷新的增量，再同步发布不可覆盖的终场状态。
     *
     * @param decision 类型为 {@code WINDOW_CLOSED} 的权威结果
     */
    public void publishWindowClosed(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        if (!"WINDOW_CLOSED".equals(decision.type())) {
            throw new IllegalArgumentException("terminal public event requires WINDOW_CLOSED decision");
        }
        WindowUpdates updates = windows.get(decision.auctionWindowId(), ignored -> new WindowUpdates());
        int deltaCount = updates.publishWindowClosed(decision, publisher);
        if (deltaCount > 0) {
            performanceMetrics.recordPublicUpdateBatch(deltaCount);
        }
    }

    /** 启动固定周期的窗口增量合并任务。 */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        flushTask = scheduler.scheduleWithFixedDelay(this::flushSafely, flushInterval);
        running = true;
    }

    /** 停止合并任务，并尽力刷出进程内尚未发送的公共状态。 */
    @Override
    public synchronized void stop() {
        ScheduledFuture<?> current = flushTask;
        flushTask = null;
        running = false;
        if (current != null) {
            current.cancel(false);
        }
        flushSafely();
    }

    /** 返回公共状态合并任务是否已启动。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** 让公共通知晚于普通业务组件启动、早于底层消息设施停止。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    void flushNow() {
        windows.asMap().values().forEach(updates -> {
            int deltaCount = updates.publishPending(publisher);
            if (deltaCount > 0) {
                performanceMetrics.recordPublicUpdateBatch(deltaCount);
            }
        });
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (RuntimeException exception) {
            LOGGER.warn("Promotion public update flush failed and remains pending", exception);
        }
    }

    private static final class WindowUpdates {

        private final Map<String, PromotionBidDelta> deltas = new LinkedHashMap<>();
        private long auctionWindowId;
        private String latestDecisionId;
        private long latestDecisionVersion;
        private long publicEventVersion;
        private java.time.Instant latestOccurredAt;

        private synchronized void merge(PromotionAuctionDecision decision) {
            PromotionBidDelta delta = new PromotionBidDelta(
                    String.valueOf(decision.campaignId()),
                    String.valueOf(decision.bidderUserId()),
                    String.valueOf(decision.postId()),
                    decision.bidAmount());
            deltas.put(delta.campaignId(), delta);
            auctionWindowId = decision.auctionWindowId();
            latestDecisionId = decision.decisionId();
            latestDecisionVersion = decision.decisionVersion();
            latestOccurredAt = decision.decidedAt();
        }

        private synchronized boolean hasPending() {
            return !deltas.isEmpty();
        }

        private synchronized int publishPending(PromotionAuctionRealtimePublisher publisher) {
            if (deltas.isEmpty()) {
                return 0;
            }
            long nextEventVersion = publicEventVersion + 1L;
            List<PromotionBidDelta> batch = new ArrayList<>(deltas.values());
            PromotionAuctionRealtimeEvent event = new PromotionAuctionRealtimeEvent(
                    "window-" + latestDecisionId + ":public:" + nextEventVersion,
                    PromotionAuctionRealtimeEvent.RANKING_DELTA,
                    auctionWindowId,
                    latestDecisionId,
                    latestDecisionVersion,
                    nextEventVersion,
                    "OPEN",
                    List.of(),
                    batch,
                    latestOccurredAt);
            publisher.publishPublic(event);
            deltas.clear();
            publicEventVersion = nextEventVersion;
            return batch.size();
        }

        private synchronized int publishWindowClosed(
                PromotionAuctionDecision decision,
                PromotionAuctionRealtimePublisher publisher) {
            int deltaCount = publishPending(publisher);
            long nextEventVersion = publicEventVersion + 1L;
            publisher.publishPublic(new PromotionAuctionRealtimeEvent(
                    "decision-" + decision.decisionId() + ":public:" + nextEventVersion,
                    PromotionAuctionRealtimeEvent.WINDOW_CLOSED,
                    decision.auctionWindowId(),
                    decision.decisionId(),
                    decision.decisionVersion(),
                    nextEventVersion,
                    String.valueOf(decision.payload().getOrDefault("finalWindowStatus", "SETTLED")),
                    decision.ranking(),
                    List.of(),
                    decision.decidedAt()));
            publicEventVersion = nextEventVersion;
            deltas.clear();
            return deltaCount;
        }
    }
}
