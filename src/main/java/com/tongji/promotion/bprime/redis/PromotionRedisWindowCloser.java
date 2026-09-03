package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 通过 Redis TIME 原子关窗并追加 AUCTION_SOLD / AUCTION_NO_BID Stream 终态事件。
 *
 * <p>该适配器只解释 Lua 的强类型结果，不维护任何本地或 Redis 到期索引。</p>
 *
 * @since 2026-09-03
 */
@Component
public class PromotionRedisWindowCloser {
    private static final String STATUS_NOT_DUE = "NOT_DUE";
    private static final String STATUS_ALREADY_TERMINAL = "ALREADY_TERMINAL";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionBPrimeProperties properties;
    private final DefaultRedisScript<String> closeScript;

    public PromotionRedisWindowCloser(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.closeScript = new DefaultRedisScript<>();
        this.closeScript.setLocation(new ClassPathResource("redis/lua/promotion-auction-close.lua"));
        this.closeScript.setResultType(String.class);
    }

    /**
     * 让 Redis 按其权威时间裁决一次窗口关窗。
     *
     * <p>调用幂等：已生成的关窗决策会作为 {@link PromotionRedisCloseOutcome.Closed} 重放，
     * 已由 cap-hit 等路径终结的窗口返回 {@link PromotionRedisCloseOutcome.AlreadyTerminal}。</p>
     *
     * @param auctionWindowId 拍卖窗口 ID
     * @return 已关闭、尚未到期或已经终结的强类型结果，不返回 {@code null}
     * @throws PromotionAuctionUnavailableException 当 Redis 不可用、Lua 返回 UNAVAILABLE 或结果无法解析时
     */
    public PromotionRedisCloseOutcome close(long auctionWindowId) {
        final String payload;
        try {
            payload = redisTemplate.execute(closeScript,
                    PromotionAuctionRedisKeys.closeKeys(auctionWindowId),
                    String.valueOf(auctionWindowId),
                    String.valueOf(properties.getHotStateTtlSeconds()));
        } catch (RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("promotion auction Redis close failed", exception);
        }

        try {
            return decode(payload);
        } catch (PromotionAuctionUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("failed to parse promotion close result", exception);
        }
    }

    private PromotionRedisCloseOutcome decode(String payload) throws JsonProcessingException {
        JsonNode parsed = objectMapper.readTree(payload);
        if (!(parsed instanceof ObjectNode node)) {
            throw new IllegalArgumentException("promotion close result must be a JSON object");
        }

        if (node.has("status")) {
            return switch (node.path("status").asText()) {
                case STATUS_NOT_DUE -> new PromotionRedisCloseOutcome.NotDue(
                        requiredLong(node, "redisNowEpochMs"), requiredLong(node, "deadlineEpochMs"));
                case STATUS_ALREADY_TERMINAL -> new PromotionRedisCloseOutcome.AlreadyTerminal();
                case STATUS_UNAVAILABLE -> throw new PromotionAuctionUnavailableException(
                        node.path("rejectionReason").asText());
                default -> throw new IllegalArgumentException(
                        "unsupported promotion close status: " + node.path("status").asText());
            };
        }

        node.put("decidedAt", Instant.ofEpochMilli(requiredLong(node, "decidedAtEpochMs")).toString());
        normalizeArray(node, "ranking");
        normalizeArray(node, "walletEffects");
        node.remove("decidedAtEpochMs");
        PromotionAuctionDecision decision = objectMapper.treeToValue(node, PromotionAuctionDecision.class);
        return new PromotionRedisCloseOutcome.Closed(decision);
    }

    private long requiredLong(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("promotion close result requires integer field: " + fieldName);
        }
        return value.longValue();
    }

    private void normalizeArray(ObjectNode node, String fieldName) {
        if (node.path(fieldName).isObject() && node.path(fieldName).isEmpty()) {
            node.set(fieldName, objectMapper.createArrayNode());
        }
    }
}
