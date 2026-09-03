package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionHotSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Reads a consistent hot auction snapshot in one Redis round trip. */
@Component
public class PromotionRedisSnapshotAdapter {

    private static final int MAX_RANKING_SIZE = 30;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<String> script;

    public PromotionRedisSnapshotAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/lua/promotion-auction-snapshot.lua"));
        this.script.setResultType(String.class);
    }

    public PromotionAuctionHotSnapshot snapshot(long auctionWindowId) {
        String prefix = PromotionAuctionRedisKeys.prefix(auctionWindowId);
        String payload = redisTemplate.execute(script,
                List.of(prefix + ":ranking", prefix + ":state"),
                prefix + ":campaign:", String.valueOf(MAX_RANKING_SIZE));
        try {
            JsonNode root = objectMapper.readTree(payload);
            List<PromotionRankingItem> ranking = new ArrayList<>();
            for (JsonNode item : root.path("ranking")) {
                ranking.add(new PromotionRankingItem(
                        item.path("campaignId").asText(),
                        item.path("bidderUserId").asText(),
                        item.path("postId").asText(),
                        item.path("bidAmount").asLong(),
                        item.path("rank").asInt()));
            }
            JsonNode rules = root.path("rules");
            return new PromotionAuctionHotSnapshot(
                    root.path("decisionVersion").asLong(),
                    List.copyOf(ranking),
                    root.path("currentPriceCents").asLong(),
                    root.path("status").asText("OPEN"),
                    root.path("winnerCampaignId").asText(""),
                    root.path("windowEndAtEpochMs").asLong(),
                    root.path("bidCount").asLong(),
                    new PromotionAuctionHotSnapshot.AuctionRules(
                            rules.path("stepCents").asLong(),
                            rules.hasNonNull("capCents") && rules.path("capCents").asLong() > 0
                                    ? rules.path("capCents").asLong() : null,
                            rules.path("reserveCents").asLong()));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse promotion Redis snapshot", exception);
        }
    }
}
