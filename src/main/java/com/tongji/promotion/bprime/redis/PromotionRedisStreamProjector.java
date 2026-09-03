package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.bprime.service.PromotionAuctionHotStateLifecycle;
import com.tongji.promotion.bprime.service.PromotionDecisionStreamConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pub/Sub and scheduled-scan adapters for the decision Stream consumer. */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionRedisStreamProjector implements MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionRedisStreamProjector.class);

    private final StringRedisTemplate redisTemplate;
    private final PromotionDecisionStreamConsumer streamConsumer;
    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;
    private final PromotionAuctionAvailabilityGate availabilityGate;
    private final AtomicBoolean registryRecovered = new AtomicBoolean();

    public PromotionRedisStreamProjector(StringRedisTemplate redisTemplate,
                                         PromotionDecisionStreamConsumer streamConsumer,
                                         PromotionAuctionHotStateLifecycle hotStateLifecycle,
                                         PromotionAuctionAvailabilityGate availabilityGate) {
        this.redisTemplate = redisTemplate;
        this.streamConsumer = streamConsumer;
        this.hotStateLifecycle = hotStateLifecycle;
        this.availabilityGate = availabilityGate;
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
        if (!availabilityGate.allowsProjection()) {
            return;
        }
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

    public void processWindow(long auctionWindowId) {
        if (availabilityGate.allowsProjection()) {
            streamConsumer.consumeWindow(auctionWindowId);
        }
    }

    private void recoverRegistryOnce() {
        if (!registryRecovered.compareAndSet(false, true)) {
            return;
        }
        try {
            hotStateLifecycle.recoverActiveWindows();
        } catch (RuntimeException exception) {
            registryRecovered.set(false);
            throw exception;
        }
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
