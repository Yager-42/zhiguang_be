package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.service.PromotionDecisionFanoutService;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pub/Sub 仅触发读取；活跃窗口注册表与 MySQL 启动恢复保证丢失唤醒后仍从 Stream checkpoint 恢复。
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionRedisStreamProjector implements MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionRedisStreamProjector.class);
    private static final String INITIAL_STREAM_ID = "0-0";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionDecisionProjectionService projectionService;
    private final PromotionDecisionFanoutService fanoutService;
    private final PromotionPerformanceMetrics metrics;
    private final PromotionBPrimeProperties properties;
    private final DefaultRedisScript<Long> trimScript;
    private final Map<Long, ReentrantLock> windowLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean registryRecovered = new AtomicBoolean();

    public PromotionRedisStreamProjector(StringRedisTemplate redisTemplate,
                                         ObjectMapper objectMapper,
                                         PromotionProjectionCheckpointMapper checkpointMapper,
                                         PromotionAuctionWindowMapper windowMapper,
                                         PromotionDecisionProjectionService projectionService,
                                         PromotionDecisionFanoutService fanoutService,
                                         PromotionPerformanceMetrics metrics,
                                         PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.checkpointMapper = checkpointMapper;
        this.windowMapper = windowMapper;
        this.projectionService = projectionService;
        this.fanoutService = fanoutService;
        this.metrics = metrics;
        this.properties = properties;
        this.trimScript = new DefaultRedisScript<>();
        this.trimScript.setLocation(new ClassPathResource("redis/lua/promotion-auction-trim.lua"));
        this.trimScript.setResultType(Long.class);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        Long windowId = parseWindowId(channel);
        if (windowId != null) {
            processWindow(windowId);
        }
    }

    @Scheduled(
            fixedDelayString = "${promotion.bprime.stream-sweep-interval-ms:2000}",
            initialDelayString = "${promotion.bprime.stream-sweep-initial-delay-ms:0}")
    public void sweep() {
        try {
            recoverRegistryOnce();
            Set<String> activeWindowIds = redisTemplate.opsForSet().members(
                    PromotionAuctionRedisKeys.activeStreams());
            if (activeWindowIds == null || activeWindowIds.isEmpty()) {
                return;
            }
            for (String activeWindowId : activeWindowIds) {
                try {
                    processWindow(Long.parseLong(activeWindowId));
                } catch (NumberFormatException exception) {
                    redisTemplate.opsForSet().remove(PromotionAuctionRedisKeys.activeStreams(), activeWindowId);
                    LOGGER.warn("移除非法推广竞价活跃窗口标识: {}", activeWindowId);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("推广竞价活跃 Stream 恢复失败", exception);
        }
    }

    private void recoverRegistryOnce() {
        if (!registryRecovered.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PromotionAuctionWindow> activeWindows = windowMapper.listActiveRedisStreamWindows();
            if (activeWindows == null || activeWindows.isEmpty()) {
                return;
            }
            String[] windowIds = activeWindows.stream()
                    .map(window -> String.valueOf(window.getId()))
                    .toArray(String[]::new);
            redisTemplate.opsForSet().add(PromotionAuctionRedisKeys.activeStreams(), windowIds);
        } catch (RuntimeException exception) {
            registryRecovered.set(false);
            throw exception;
        }
    }

    public void processWindow(long auctionWindowId) {
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
                recordStreamState(auctionWindowId);
            } catch (RuntimeException exception) {
                LOGGER.error("推广竞价 Stream 投影暂停，auctionWindowId={}", auctionWindowId, exception);
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
            items.forEach(item -> {
                if (fanoutService.publishDecision(item.decision())) {
                    metrics.recordRealtimeComplete(item.decision());
                }
            });
            List<PromotionAuctionDecision> projected = projectionService.projectBatch(items);
            projectedAny = true;
            projected.forEach(metrics::recordProjectionComplete);
            afterStreamId = items.get(items.size() - 1).streamId();
            trimProjectedEvents(auctionWindowId, items.get(items.size() - 1).decision().decisionVersion());
            if (items.size() < properties.getStreamReadBatchSize()) {
                return true;
            }
        }
    }

    private void recordStreamState(long auctionWindowId) {
        String streamKey = PromotionAuctionRedisKeys.events(auctionWindowId);
        Long length = redisTemplate.opsForStream().size(streamKey);
        List<MapRecord<String, Object, Object>> latest = redisTemplate.opsForStream().reverseRange(
                streamKey, Range.unbounded(), Limit.limit().count(1));
        long latestVersion = latest == null || latest.isEmpty()
                ? 0L : streamVersion(latest.getFirst().getId().getValue());
        PromotionProjectionCheckpointRecord checkpoint =
                checkpointMapper.findByAuctionWindowId(auctionWindowId);
        long checkpointVersion = checkpoint == null ? 0L : checkpoint.getLastDecisionVersion();
        metrics.recordStreamState(length == null ? 0L : length,
                Math.max(0L, latestVersion - checkpointVersion));
        if (latestVersion == checkpointVersion) {
            expireSettledHotState(auctionWindowId);
        }
    }

    private void expireSettledHotState(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        if (window == null || window.getStatus() != PromotionAuctionWindowStatus.SETTLED) {
            return;
        }
        Duration ttl = Duration.ofSeconds(properties.getHotStateTtlSeconds());
        redisTemplate.opsForHash().put(PromotionAuctionRedisKeys.state(auctionWindowId), "status", "SETTLED");
        redisTemplate.expire(PromotionAuctionRedisKeys.state(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.ranking(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.escrow(auctionWindowId), ttl);
        redisTemplate.expire(PromotionAuctionRedisKeys.events(auctionWindowId), ttl);
        redisTemplate.opsForSet().remove(PromotionAuctionRedisKeys.activeStreams(), String.valueOf(auctionWindowId));
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
                node.put("decidedAt",
                        Instant.ofEpochMilli(node.get("decidedAtEpochMs").asLong()).toString());
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

    private Long parseWindowId(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}', open + 1);
        if (open < 0 || close <= open + 1) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(open + 1, close));
        } catch (NumberFormatException exception) {
            LOGGER.warn("忽略非法推广竞价 Redis key: {}", key);
            return null;
        }
    }
}
