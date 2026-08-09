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

    public PromotionAuctionDecision decide(PromotionAuctionCommand command, Instant now) {
        List<String> keys = PromotionAuctionRedisKeys.decisionKeys(command.auctionWindowId(), command.campaignId());
        String payload = redisTemplate.execute(decisionScript, keys,
                command.commandId(),
                command.requestHash(),
                String.valueOf(command.bidderUserId()),
                String.valueOf(command.bidAmount()),
                String.valueOf(command.reservePrice()),
                String.valueOf(now.toEpochMilli()),
                command.windowStatus(),
                String.valueOf(command.auctionWindowId()),
                String.valueOf(command.campaignId()),
                String.valueOf(command.postId()),
                command.resourceType(),
                String.valueOf(properties.getHotStateTtlSeconds()),
                command.submittedAt().toString(),
                command.type(),
                String.valueOf(properties.getCommandIdempotencyTtlSeconds()));
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            if (!node.hasNonNull("decidedAt") && node.hasNonNull("decidedAtEpochMs")) {
                node.put("decidedAt", Instant.ofEpochMilli(node.get("decidedAtEpochMs").asLong()).toString());
            }
            normalizeEmptyArray(node, "ranking");
            normalizeEmptyArray(node, "walletEffects");
            node.remove("decidedAtEpochMs");
            return objectMapper.treeToValue(node, PromotionAuctionDecision.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse promotion redis decision", e);
        }
    }

    private void normalizeEmptyArray(ObjectNode node, String fieldName) {
        if (node.path(fieldName).isObject() && node.path(fieldName).isEmpty()) {
            node.set(fieldName, objectMapper.createArrayNode());
        }
    }

}
