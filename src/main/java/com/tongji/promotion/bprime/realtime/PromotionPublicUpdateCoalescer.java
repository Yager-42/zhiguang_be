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
import java.util.concurrent.TimeUnit;

/**
 * 按窗口合并高频竞价增量，并使用独立调度资源发布可恢复的公共状态。
 *
 * <p>同一 flush 周期内每个 campaign 只保留最高版本。公共通知可被慢连接覆盖，客户端通过连续
 * {@code eventVersion} 检测缺帧并读取 snapshot。</p>
 *
 * @since 2026-08-09
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionPublicUpdateCoalescer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionPublicUpdateCoalescer.class);

    private final PromotionAuctionRealtimePublisher publisher;
    private final ThreadPoolTaskScheduler scheduler;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final Duration schedulerTick;
    private final long minimumFlushIntervalNanos;
    private final long maximumFlushIntervalNanos;
    private final int adaptiveSubscriberCeiling;
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
        this.schedulerTick = Duration.ofMillis(properties.getPublicUpdateFlushIntervalMs());
        this.minimumFlushIntervalNanos = schedulerTick.toNanos();
        this.maximumFlushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getPublicUpdateMaximumFlushIntervalMs());
        this.adaptiveSubscriberCeiling = properties.getPublicUpdateAdaptiveSubscriberCeiling();
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
     * @param decision 类型为 {@code AUCTION_SOLD} / {@code AUCTION_NO_BID} 的终态权威结果
     */
    public void publishWindowClosed(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        if (!"AUCTION_SOLD".equals(decision.type()) && !"AUCTION_NO_BID".equals(decision.type())) {
            throw new IllegalArgumentException("terminal public event requires AUCTION_SOLD/AUCTION_NO_BID decision");
        }
        WindowUpdates updates = windows.get(decision.auctionWindowId(), ignored -> new WindowUpdates());
        int deltaCount = updates.publishWindowClosed(decision, publisher);
        if (deltaCount > 0) {
            performanceMetrics.recordPublicUpdateBatch(deltaCount);
        }
    }

    /**
     * 反狙击延长事件（低频，直接发布不合并）：携带新窗口结束时间与延长次数。
     *
     * @param decision 类型为 {@code AUCTION_EXTENDED} 的权威结果
     */
    public void publishWindowExtended(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        PromotionAuctionDecision.ExtensionFacts facts = decision.extensionFacts();
        long nextEventVersion = decision.decisionVersion();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endAtEpochMs", facts.endAtEpochMs());
        details.put("extendCount", facts.extendCount());
        publisher.publishPublic(new PromotionAuctionRealtimeEvent(
                "decision-" + decision.decisionId() + ":public:" + nextEventVersion,
                PromotionAuctionRealtimeEvent.AUCTION_EXTENDED,
                String.valueOf(decision.auctionWindowId()),
                decision.decisionId(),
                decision.decisionVersion(),
                nextEventVersion,
                decision.decisionVersion(),
                decision.decisionVersion(),
                "OPEN",
                List.of(),
                List.of(),
                details,
                decision.decidedAt()));
    }

    /** 启动基础 tick；各窗口按订阅数与待写槽压力决定实际刷新周期。 */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        flushTask = scheduler.scheduleWithFixedDelay(this::flushSafely, schedulerTick);
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
        flushNowSafely();
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

    private void flushDue() {
        long nowNanos = System.nanoTime();
        windows.asMap().values().forEach(updates -> {
            int deltaCount = updates.publishPendingIfDue(
                    publisher, nowNanos, adaptiveFlushIntervalNanos(updates.auctionWindowId()));
            if (deltaCount > 0) {
                performanceMetrics.recordPublicUpdateBatch(deltaCount);
            }
        });
    }

    private long adaptiveFlushIntervalNanos(long auctionWindowId) {
        int subscriberCount = Math.max(0, publisher.publicSubscriberCount(auctionWindowId));
        int pendingMessageCount = Math.max(0, publisher.pendingPublicMessageCount(auctionWindowId));
        long intervalRange = maximumFlushIntervalNanos - minimumFlushIntervalNanos;
        long subscriberPressure = intervalRange
                * Math.min(subscriberCount, adaptiveSubscriberCeiling) / adaptiveSubscriberCeiling;
        long queuePressure = subscriberCount == 0 ? 0L
                : intervalRange * Math.min(pendingMessageCount, subscriberCount) / subscriberCount;
        return minimumFlushIntervalNanos + Math.max(subscriberPressure, queuePressure);
    }

    private void flushSafely() {
        try {
            flushDue();
        } catch (RuntimeException exception) {
            LOGGER.warn("Promotion public update flush failed and remains pending", exception);
        }
    }

    private void flushNowSafely() {
        try {
            flushNow();
        } catch (RuntimeException exception) {
            LOGGER.warn("Promotion public update final flush failed and remains pending", exception);
        }
    }

    private static final class WindowUpdates {

        private final Map<String, PromotionBidDelta> deltas = new LinkedHashMap<>();
        private long auctionWindowId;
        private String latestDecisionId;
        private long latestDecisionVersion;
        private long pendingFromDecisionVersion;
        private java.time.Instant latestOccurredAt;
        private Object latestWinnerCampaignId;
        private Object latestCurrentPriceCents;
        private Object latestNextRequiredAmount;
        private long lastPublishedAtNanos;

        private synchronized void merge(PromotionAuctionDecision decision) {
            PromotionAuctionDecision.AdmissionFacts facts = decision.admissionFacts();
            PromotionAuctionDecision.BidFacts bidFacts = decision.bidFacts();
            if (deltas.isEmpty()) {
                pendingFromDecisionVersion = decision.decisionVersion();
            }
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
            latestWinnerCampaignId = facts.winnerCampaignId();
            latestCurrentPriceCents = facts.currentPriceCents();
            latestNextRequiredAmount = bidFacts.nextRequiredAmount().orElse(0L);
        }

        private synchronized boolean hasPending() {
            return !deltas.isEmpty();
        }

        private synchronized long auctionWindowId() {
            return auctionWindowId;
        }

        private synchronized int publishPendingIfDue(
                PromotionAuctionRealtimePublisher publisher, long nowNanos, long flushIntervalNanos) {
            if (deltas.isEmpty()
                    || (lastPublishedAtNanos != 0L
                    && nowNanos - lastPublishedAtNanos < flushIntervalNanos)) {
                return 0;
            }
            return publishPending(publisher, nowNanos);
        }

        private synchronized int publishPending(PromotionAuctionRealtimePublisher publisher) {
            return publishPending(publisher, System.nanoTime());
        }

        private int publishPending(PromotionAuctionRealtimePublisher publisher, long publishedAtNanos) {
            if (deltas.isEmpty()) {
                return 0;
            }
            long nextEventVersion = latestDecisionVersion;
            List<PromotionBidDelta> batch = new ArrayList<>(deltas.values());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("winnerCampaignId", latestWinnerCampaignId);
            details.put("currentPriceCents", latestCurrentPriceCents);
            details.put("nextRequiredAmount", latestNextRequiredAmount);
            details.put("decisionVersion", latestDecisionVersion);
            PromotionAuctionRealtimeEvent event = new PromotionAuctionRealtimeEvent(
                    "window-" + latestDecisionId + ":public:" + nextEventVersion,
                    PromotionAuctionRealtimeEvent.RANKING_DELTA,
                    String.valueOf(auctionWindowId),
                    latestDecisionId,
                    latestDecisionVersion,
                    nextEventVersion,
                    pendingFromDecisionVersion,
                    latestDecisionVersion,
                    "OPEN",
                    List.of(),
                    batch,
                    details,
                    latestOccurredAt);
            publisher.publishPublic(event);
            deltas.clear();
            pendingFromDecisionVersion = 0L;
            lastPublishedAtNanos = publishedAtNanos;
            return batch.size();
        }

        private synchronized int publishWindowClosed(
                PromotionAuctionDecision decision,
                PromotionAuctionRealtimePublisher publisher) {
            PromotionAuctionDecision.TerminalFacts facts = decision.terminalFacts();
            int deltaCount = publishPending(publisher);
            long nextEventVersion = decision.decisionVersion();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("terminalStatus", decision.type());
            details.put("winnerCampaignId", facts.winnerCampaignId().map(String::valueOf).orElse(""));
            details.put("winningAmount", facts.winningAmount().orElse(0L));
            details.put("actualEndAtEpochMs", facts.actualEndAtEpochMs());
            publisher.publishPublic(new PromotionAuctionRealtimeEvent(
                    "decision-" + decision.decisionId() + ":public:" + nextEventVersion,
                    PromotionAuctionRealtimeEvent.WINDOW_CLOSED,
                    String.valueOf(decision.auctionWindowId()),
                    decision.decisionId(),
                    decision.decisionVersion(),
                    nextEventVersion,
                    decision.decisionVersion(),
                    decision.decisionVersion(),
                    facts.finalWindowStatus(),
                    decision.ranking(),
                    List.of(),
                    details,
                    decision.decidedAt()));
            pendingFromDecisionVersion = 0L;
            deltas.clear();
            return deltaCount;
        }
    }
}
