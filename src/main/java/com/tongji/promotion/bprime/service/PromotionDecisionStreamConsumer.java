package com.tongji.promotion.bprime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.redis.PromotionAuctionRedisKeys;
import com.tongji.promotion.bprime.redis.PromotionBidAdmissionState;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 消费单窗口决策 Stream，推进实时发布与 MySQL checkpoint，并在终态投影成功后取消本地 deadline。
 * 仅当 checkpoint 追平最新 Stream 版本后，才退休已结算窗口的 Redis 热状态。
 */
@Service
public class PromotionDecisionStreamConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionDecisionStreamConsumer.class);
    private static final String INITIAL_STREAM_ID = "0-0";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionDecisionProjectionService projectionService;
    private final PromotionDecisionFanoutService fanoutService;
    private final PromotionPerformanceMetrics metrics;
    private final PromotionBPrimeProperties properties;
    private final PromotionBidAdmissionState admissionState;
    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;
    private final PromotionAuctionDeadlineManager deadlineManager;
    private final DefaultRedisScript<Long> trimScript;
    private final Map<Long, ReentrantLock> windowLocks = new ConcurrentHashMap<>();

    public PromotionDecisionStreamConsumer(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           PromotionProjectionCheckpointMapper checkpointMapper,
                                           PromotionDecisionProjectionService projectionService,
                                           PromotionDecisionFanoutService fanoutService,
                                           PromotionPerformanceMetrics metrics,
                                           PromotionBPrimeProperties properties,
                                           PromotionBidAdmissionState admissionState,
                                           PromotionAuctionHotStateLifecycle hotStateLifecycle,
                                           PromotionAuctionDeadlineManager deadlineManager) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointMapper = checkpointMapper;
        this.projectionService = projectionService;
        this.fanoutService = fanoutService;
        this.metrics = metrics;
        this.properties = properties;
        this.admissionState = admissionState;
        this.hotStateLifecycle = hotStateLifecycle;
        this.deadlineManager = deadlineManager;
        this.trimScript = new DefaultRedisScript<>();
        this.trimScript.setLocation(new ClassPathResource("redis/lua/promotion-auction-trim.lua"));
        this.trimScript.setResultType(Long.class);
    }

    public void consumeWindow(long auctionWindowId) {
        ReentrantLock lock = windowLocks.computeIfAbsent(auctionWindowId, ignored -> new ReentrantLock());
        boolean rerun;
        do {
            if (!lock.tryLock()) {
                return;
            }
            long startedAt = System.nanoTime();
            boolean drained = false;
            try {
                redisTemplate.delete(PromotionAuctionRedisKeys.publicationWakeup(auctionWindowId));
                drained = projectAvailable(auctionWindowId);
                recordStableState(auctionWindowId);
            } catch (RuntimeException exception) {
                LOGGER.error("推广竞价 Stream 消费暂停，auctionWindowId={}", auctionWindowId, exception);
            } finally {
                if (drained) {
                    metrics.recordStreamDrain(System.nanoTime() - startedAt);
                }
                lock.unlock();
            }
            try {
                rerun = Boolean.TRUE.equals(
                        redisTemplate.hasKey(PromotionAuctionRedisKeys.publicationWakeup(auctionWindowId)));
            } catch (RuntimeException exception) {
                LOGGER.warn("推广竞价 Stream 唤醒状态读取失败，auctionWindowId={}", auctionWindowId, exception);
                rerun = false;
            }
        } while (rerun);
    }

    private boolean projectAvailable(long auctionWindowId) {
        String afterStreamId = checkpointStreamId(auctionWindowId);
        boolean projectedAny = false;
        while (true) {
            List<PromotionDecisionProjectionItem> items = readPage(auctionWindowId, afterStreamId);
            if (items.isEmpty()) {
                return projectedAny;
            }
            for (PromotionDecisionProjectionItem item : items) {
                PromotionAuctionDecision decision = item.decision();
                if (decision.affectsAdmissionState()) {
                    admissionState.update(decision);
                }
                if (fanoutService.publishDecision(decision)) {
                    metrics.recordRealtimeComplete(decision);
                }
            }
            List<PromotionAuctionDecision> projected = projectionService.projectBatch(items);
            for (PromotionAuctionDecision decision : projected) {
                if (decision.terminal()) {
                    deadlineManager.cancel(decision.auctionWindowId());
                }
            }
            projectedAny = true;
            projected.forEach(metrics::recordProjectionComplete);
            afterStreamId = items.getLast().streamId();
            trimProjectedEvents(auctionWindowId, items.getLast().decision().decisionVersion());
            if (items.size() < properties.getStreamReadBatchSize()) {
                return true;
            }
        }
    }

    private void recordStableState(long auctionWindowId) {
        String streamKey = PromotionAuctionRedisKeys.events(auctionWindowId);
        Long length = redisTemplate.opsForStream().size(streamKey);
        List<MapRecord<String, Object, Object>> latest = redisTemplate.opsForStream().reverseRange(
                streamKey, Range.unbounded(), Limit.limit().count(1));
        long latestVersion = latest == null || latest.isEmpty()
                ? 0L : streamVersion(latest.getFirst().getId().getValue());
        PromotionProjectionCheckpointRecord checkpoint = checkpointMapper.findByAuctionWindowId(auctionWindowId);
        long checkpointVersion = checkpoint == null ? 0L : checkpoint.getLastDecisionVersion();
        metrics.recordStreamState(length == null ? 0L : length, Math.max(0L, latestVersion - checkpointVersion));
        if (latestVersion == checkpointVersion) {
            hotStateLifecycle.retireSettledState(auctionWindowId);
        }
    }

    private long streamVersion(String streamId) {
        int separator = streamId.indexOf('-');
        if (separator <= 0) {
            throw new IllegalStateException("invalid promotion Stream id: " + streamId);
        }
        return Long.parseLong(streamId.substring(0, separator));
    }

    private List<PromotionDecisionProjectionItem> readPage(long auctionWindowId, String afterStreamId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                PromotionAuctionRedisKeys.events(auctionWindowId),
                Range.open(afterStreamId, "+"),
                Limit.limit().count(properties.getStreamReadBatchSize()));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<PromotionDecisionProjectionItem> items = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get("decision");
            if (payload == null) {
                throw new IllegalStateException("promotion Stream event is missing decision: " + record.getId());
            }
            items.add(new PromotionDecisionProjectionItem(
                    parseDecision(String.valueOf(payload)), record.getId().getValue()));
        }
        return List.copyOf(items);
    }

    private PromotionAuctionDecision parseDecision(String payload) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            if (!node.hasNonNull("decidedAt") && node.hasNonNull("decidedAtEpochMs")) {
                node.put("decidedAt", Instant.ofEpochMilli(node.get("decidedAtEpochMs").asLong()).toString());
            }
            normalizeArray(node, "ranking");
            normalizeArray(node, "walletEffects");
            node.remove("decidedAtEpochMs");
            return objectMapper.treeToValue(node, PromotionAuctionDecision.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid promotion Stream decision", exception);
        }
    }

    private void normalizeArray(ObjectNode node, String fieldName) {
        if (node.path(fieldName).isObject() && node.path(fieldName).isEmpty()) {
            node.set(fieldName, objectMapper.createArrayNode());
        }
    }

    private String checkpointStreamId(long auctionWindowId) {
        PromotionProjectionCheckpointRecord checkpoint = checkpointMapper.findByAuctionWindowId(auctionWindowId);
        return checkpoint == null ? INITIAL_STREAM_ID : checkpoint.getLastStreamId();
    }

    private void trimProjectedEvents(long auctionWindowId, long checkpointVersion) {
        long firstRetainedVersion = checkpointVersion - properties.getStreamRetainEvents() + 1L;
        if (firstRetainedVersion <= 1L) {
            return;
        }
        Long trimmed = redisTemplate.execute(trimScript,
                List.of(PromotionAuctionRedisKeys.events(auctionWindowId)),
                firstRetainedVersion + "-0");
        metrics.recordStreamTrimmed(trimmed == null ? 0L : trimmed);
    }
}
