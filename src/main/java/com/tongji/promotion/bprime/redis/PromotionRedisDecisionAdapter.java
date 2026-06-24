package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PromotionRedisDecisionAdapter {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<String> script;

    public PromotionRedisDecisionAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/lua/promotion-auction-decision.lua"));
        this.script.setResultType(String.class);
    }

    public PromotionAuctionDecision decide(PromotionAuctionCommand command, long reservePrice, String windowStatus,
                                           Instant now) {
        List<String> keys = PromotionAuctionRedisKeys.decisionKeys(command.auctionWindowId(), command.campaignId());
        String payload = redisTemplate.execute(script, keys,
                command.commandId(),
                command.requestHash(),
                String.valueOf(command.bidderUserId()),
                String.valueOf(command.bidAmount()),
                String.valueOf(reservePrice),
                String.valueOf(now.toEpochMilli()),
                windowStatus,
                String.valueOf(command.auctionWindowId()),
                String.valueOf(command.campaignId()),
                String.valueOf(command.postId()),
                command.resourceType());
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            if (!node.hasNonNull("decidedAt") && node.hasNonNull("decidedAtEpochMs")) {
                node.put("decidedAt", Instant.ofEpochMilli(node.get("decidedAtEpochMs").asLong()).toString());
            }
            node.remove("decidedAtEpochMs");
            return objectMapper.treeToValue(node, PromotionAuctionDecision.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse promotion redis decision", e);
        }
    }

    public void commit(PromotionAuctionDecision decision) {
        if (!decision.accepted()) {
            return;
        }
        String prefix = "promotion:auction:" + decision.auctionWindowId();
        String campaignKey = prefix + ":campaign:" + decision.campaignId();
        Map<String, String> fields = new HashMap<>();
        fields.put("bidAmount", String.valueOf(decision.bidAmount()));
        fields.put("bidderUserId", String.valueOf(decision.bidderUserId()));
        fields.put("postId", String.valueOf(decision.postId()));
        fields.put("updatedAt", String.valueOf(decision.decidedAt().toEpochMilli()));
        redisTemplate.opsForHash().putAll(campaignKey, fields);
        double score = (decision.bidAmount() * 1_000_000_000_000D) - decision.decidedAt().toEpochMilli();
        redisTemplate.opsForZSet().add(prefix + ":ranking", String.valueOf(decision.campaignId()), score);
        redisTemplate.opsForHash().put(prefix + ":state", "status", "OPEN");
        redisTemplate.opsForHash().put(prefix + ":state", "updatedAt", String.valueOf(decision.decidedAt().toEpochMilli()));
    }

    public void rollback(PromotionAuctionDecision decision) {
        String prefix = "promotion:auction:" + decision.auctionWindowId();
        String commandsKey = prefix + ":commands";
        redisTemplate.opsForHash().delete(commandsKey,
                decision.commandId() + ":hash",
                decision.commandId() + ":decision");
        String versionKey = prefix + ":decision_version";
        String currentVersion = redisTemplate.opsForValue().get(versionKey);
        if (currentVersion != null && currentVersion.equals(String.valueOf(decision.decisionVersion()))) {
            redisTemplate.opsForValue().set(versionKey, String.valueOf(decision.previousVersion()));
        }
    }
}
