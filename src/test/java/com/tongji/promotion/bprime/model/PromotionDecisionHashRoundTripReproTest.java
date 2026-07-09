package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现 bprime 结算链路 decision hash mismatch。
 * 怀疑：payload 是 Map<String,Object>，里头的 List<PromotionWalletEffect> / List<PromotionRankingItem>
 * 在生产者侧是 record（CANONICAL_MAPPER 按 record 声明顺序序列化），
 * 消费者反序列化 Map<String,Object> 后退化成 LinkedHashMap（ORDER_MAP_ENTRIES_BY_KEYS 按字母序），
 * 两者 JSON 字符串不同 → SHA-256 不同 → hash mismatch。
 */
class PromotionDecisionHashRoundTripReproTest {

    private final ObjectMapper springMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void windowClosedDecisionHashSurvivesRoundTrip() throws Exception {
        PromotionAuctionDecision original = windowClosedDecision();
        String hashBefore = PromotionDecisionHasher.hash(original);

        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                original, hashBefore, Instant.parse("2026-07-08T11:00:01Z"));
        String json = springMapper.writeValueAsString(envelope);
        PromotionAuctionDecisionLogEnvelope rebuilt = springMapper.readValue(json, PromotionAuctionDecisionLogEnvelope.class);
        String hashAfter = PromotionDecisionHasher.hash(rebuilt.decision());

        System.out.println("HASH_BEFORE=" + hashBefore);
        System.out.println("HASH_AFTER =" + hashAfter);

        assertThat(hashAfter).isEqualTo(hashBefore);
    }

    @Test
    void bidAcceptedDecisionHashSurvivesRoundTrip() throws Exception {
        PromotionAuctionDecision original = bidAcceptedDecision();
        String hashBefore = PromotionDecisionHasher.hash(original);

        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                original, hashBefore, Instant.parse("2026-07-08T10:05:01Z"));
        String json = springMapper.writeValueAsString(envelope);
        PromotionAuctionDecisionLogEnvelope rebuilt = springMapper.readValue(json, PromotionAuctionDecisionLogEnvelope.class);
        String hashAfter = PromotionDecisionHasher.hash(rebuilt.decision());

        assertThat(hashAfter).isEqualTo(hashBefore);
    }

    private PromotionAuctionDecision bidAcceptedDecision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 2L, 1L, 1895700000000000201L,
                1895700000000000042L, 1895700000000001001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L,
                List.of(new PromotionRankingItem("1895700000000000201", "1895700000000000042",
                        "1895700000000001001", 120L, 1)),
                List.of(new PromotionWalletEffect(1895700000000000042L, 120L, "HOLD",
                        "promotion-bprime:cmd-1:hold")),
                Map.of(), Instant.parse("2026-07-08T10:05:00Z"));
    }

    private PromotionAuctionDecision windowClosedDecision() {
        PromotionRankingItem rankItem = new PromotionRankingItem(
                "1895700000000000201", "1895700000000000042", "1895700000000001001", 120L, 1);
        PromotionWalletEffect effect = new PromotionWalletEffect(
                1895700000000000042L, 100L, "CAPTURE", "promotion-bprime:301:201:capture");
        return new PromotionAuctionDecision("d-close", "cmd-close", "window-close:301", 301L, 3L, 2L, 0L, 0L, 0L,
                "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L,
                List.of(rankItem),
                List.of(effect),
                Map.of(
                        "finalRanking", List.of(rankItem),
                        "winners", List.of(Map.of(
                                "campaignId", 1895700000000000201L,
                                "bidderUserId", 1895700000000000042L,
                                "postId", 1895700000000001001L,
                                "slotIndex", 0,
                                "clearingPrice", 100L)),
                        "walletEffects", List.of(effect),
                        "allocationStartAt", "2026-07-08T11:00:00Z",
                        "allocationEndAt", "2026-07-08T12:00:00Z",
                        "finalWindowStatus", "SETTLED"),
                Instant.parse("2026-07-08T11:00:00Z"));
    }
}
