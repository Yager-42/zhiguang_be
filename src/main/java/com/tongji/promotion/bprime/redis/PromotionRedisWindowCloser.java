package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 通过 Redis TIME 原子关窗并追加 WINDOW_CLOSED Stream 事件。
 */
@Component
public class PromotionRedisWindowCloser {

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

    public Optional<PromotionAuctionDecision> close(long auctionWindowId) {
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
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            String status = node.path("status").asText();
            if ("NOT_DUE".equals(status)) {
                return Optional.empty();
            }
            if ("UNAVAILABLE".equals(status)) {
                throw new PromotionAuctionUnavailableException(node.path("rejectionReason").asText());
            }
            node.put("decidedAt",
                    Instant.ofEpochMilli(node.path("decidedAtEpochMs").asLong()).toString());
            normalizeArray(node, "ranking");
            normalizeArray(node, "walletEffects");
            node.remove("decidedAtEpochMs");
            return Optional.of(objectMapper.treeToValue(node, PromotionAuctionDecision.class));
        } catch (Exception exception) {
            if (exception instanceof PromotionAuctionUnavailableException unavailableException) {
                throw unavailableException;
            }
            throw new PromotionAuctionUnavailableException("failed to parse promotion close result", exception);
        }
    }

    private void normalizeArray(ObjectNode node, String fieldName) {
        if (node.path(fieldName).isObject() && node.path(fieldName).isEmpty()) {
            node.set(fieldName, objectMapper.createArrayNode());
        }
    }
}
