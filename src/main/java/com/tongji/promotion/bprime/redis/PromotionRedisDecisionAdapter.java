package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PromotionRedisDecisionAdapter {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PromotionBPrimeProperties properties;
    private final DefaultRedisScript<String> decisionScript;

    public PromotionRedisDecisionAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                         PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.decisionScript = new DefaultRedisScript<>();
        this.decisionScript.setLocation(new ClassPathResource("redis/lua/promotion-auction-decision.lua"));
        this.decisionScript.setResultType(String.class);
    }

    public PromotionAuctionDecision decide(PromotionAuctionCommand command) {
        long commandBucketSeconds = properties.getCommandIdempotencyBucketSeconds();
        List<String> keys = PromotionAuctionRedisKeys.decisionKeys(
                command.auctionWindowId(), command.campaignId());
        long commandBucketTtlSeconds = Math.addExact(
                properties.getCommandIdempotencyTtlSeconds(), commandBucketSeconds);
        long wakeupTtlSeconds = Math.max(1L, (properties.getStreamSweepIntervalMs() * 2L + 999L) / 1_000L);
        final String payload;
        try {
            payload = redisTemplate.execute(decisionScript, keys,
                    command.commandId(),
                    command.requestHash(),
                    String.valueOf(command.bidderUserId()),
                    String.valueOf(command.bidAmount()),
                    String.valueOf(command.auctionWindowId()),
                    String.valueOf(command.campaignId()),
                    String.valueOf(command.postId()),
                    command.resourceType(),
                    String.valueOf(commandBucketTtlSeconds),
                    String.valueOf(properties.getHotStateTtlSeconds()),
                    command.submittedAt().toString(),
                    String.valueOf(wakeupTtlSeconds));
        } catch (RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("promotion auction Redis decision failed", exception);
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            if ("UNAVAILABLE".equals(node.path("status").asText())) {
                throw new PromotionAuctionUnavailableException(node.path("rejectionReason").asText());
            }
            if (!node.hasNonNull("decidedAt") && node.hasNonNull("decidedAtEpochMs")) {
                node.put("decidedAt", Instant.ofEpochMilli(node.get("decidedAtEpochMs").asLong()).toString());
            }
            normalizeEmptyArray(node, "ranking");
            normalizeEmptyArray(node, "walletEffects");
            node.remove("decidedAtEpochMs");
            return objectMapper.treeToValue(node, PromotionAuctionDecision.class);
        } catch (Exception e) {
            if (e instanceof PromotionAuctionUnavailableException unavailableException) {
                throw unavailableException;
            }
            throw new PromotionAuctionUnavailableException("failed to parse promotion Redis decision", e);
        }
    }

    /** 兼容迁移期间的旧调用签名；裁决时间始终取 Redis TIME。 */
    public PromotionAuctionDecision decide(PromotionAuctionCommand command, Instant ignored) {
        return decide(command);
    }

    private void normalizeEmptyArray(ObjectNode node, String fieldName) {
        if (node.path(fieldName).isObject() && node.path(fieldName).isEmpty()) {
            node.set(fieldName, objectMapper.createArrayNode());
        }
    }

}
