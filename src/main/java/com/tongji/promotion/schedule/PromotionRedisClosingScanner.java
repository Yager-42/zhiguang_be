package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.redis.PromotionAuctionRedisKeys;
import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 秒级关窗扫描：从 closing ZSET（score=windowEndAtEpochMs）取到期窗口逐个关窗。
 *
 * <p>Redis TIME 由 close.lua 二次确认（NOT_DUE 语义不变）；close.lua 幂等（closeResult
 * 重放）保证与 MySQL 30s 兜底扫描共存时无双重裁决。Go 的 auction:active + 100ms Timer 同构。</p>
 */
@Component
public class PromotionRedisClosingScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionRedisClosingScanner.class);

    private final StringRedisTemplate redisTemplate;
    private final PromotionRedisWindowCloser redisWindowCloser;
    private final int batchSize;

    public PromotionRedisClosingScanner(StringRedisTemplate redisTemplate,
                                        PromotionRedisWindowCloser redisWindowCloser,
                                        PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.redisWindowCloser = redisWindowCloser;
        this.batchSize = properties.getClosingScanBatchSize();
    }

    @Scheduled(fixedDelayString = "${promotion.bprime.closing-scan-interval-ms:1000}")
    public void scanDueWindows() {
        try {
            long nowEpochMs = Instant.now().toEpochMilli();
            Set<String> due = redisTemplate.opsForZSet().rangeByScore(
                    PromotionAuctionRedisKeys.closingIndex(), 0, nowEpochMs, 0, batchSize);
            if (due == null || due.isEmpty()) {
                return;
            }
            for (String windowId : due) {
                try {
                    redisWindowCloser.close(Long.parseLong(windowId));
                } catch (RuntimeException exception) {
                    // 单个窗口失败不影响其余窗口；下轮扫描重试（close.lua 幂等）。
                    LOGGER.error("Failed to close promotion auction window via Redis scanner, "
                            + "auctionWindowId={}", windowId, exception);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Promotion Redis closing scan failed and will retry", exception);
        }
    }
}
