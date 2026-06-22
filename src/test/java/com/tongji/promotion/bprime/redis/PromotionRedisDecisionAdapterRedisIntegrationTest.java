package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PromotionRedisDecisionAdapterRedisIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.database=0"
})
@EnabledIf("redisReachable")
class PromotionRedisDecisionAdapterRedisIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PromotionRedisDecisionAdapter adapter;

    @BeforeEach
    void cleanUp() {
        redis.delete("promotion:auction:301:state");
        redis.delete("promotion:auction:301:commands");
        redis.delete("promotion:auction:301:ranking");
        redis.delete("promotion:auction:301:campaign:243");
        redis.delete("promotion:auction:301:campaign:244");
    }

    @Test
    void acceptsAndReplaysAndRejectsInvalidCommands() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        PromotionAuctionDecision accepted = adapter.decide(command("cmd-1", "hash-1", 42L, 120L), 100L, "OPEN", now);
        PromotionAuctionDecision replay = adapter.decide(command("cmd-1", "hash-1", 42L, 120L), 100L, "OPEN", now);
        PromotionAuctionDecision conflict = adapter.decide(command("cmd-1", "hash-2", 42L, 120L), 100L, "OPEN", now);
        PromotionAuctionDecision belowReserve = adapter.decide(command("cmd-2", "hash-3", 43L, 90L), 100L, "OPEN", now);
        PromotionAuctionDecision closed = adapter.decide(command("cmd-3", "hash-4", 43L, 150L), 100L, "CLOSED", now);

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.walletEffects()).hasSize(1);
        assertThat(replay.decisionId()).isEqualTo(accepted.decisionId());
        assertThat(conflict.rejectionReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(belowReserve.rejectionReason()).isEqualTo("BELOW_RESERVE");
        assertThat(closed.rejectionReason()).isEqualTo("WINDOW_CLOSED");
    }

    @Test
    void ranksHigherBidFirst() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        adapter.commit(adapter.decide(command("cmd-1", "hash-1", 42L, 120L), 100L, "OPEN", now));
        PromotionAuctionDecision decision = adapter.decide(command("cmd-2", "hash-2", 43L, 130L), 100L, "OPEN", now.plusMillis(1));

        assertThat(decision.ranking()).extracting("bidderUserId").containsExactly(43L, 42L);
    }

    @Test
    void decideDoesNotExposeUncommittedBidToRedisHotState() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");

        PromotionAuctionDecision decision = adapter.decide(command("cmd-1", "hash-1", 42L, 120L), 100L, "OPEN", now);

        assertThat(decision.accepted()).isTrue();
        assertThat(redis.opsForZSet().reverseRange("promotion:auction:301:ranking", 0, 29)).isEmpty();
        assertThat(redis.opsForHash().entries("promotion:auction:301:campaign:243")).isEmpty();

        adapter.commit(decision);

        assertThat(redis.opsForZSet().reverseRange("promotion:auction:301:ranking", 0, 29)).containsExactly("243");
        assertThat(redis.opsForHash().get("promotion:auction:301:campaign:243", "bidAmount")).isEqualTo("120");
    }

    @Test
    void rejectsSameBidderLowerOrEqualRebid() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        adapter.commit(adapter.decide(command("cmd-1", "hash-1", 42L, 120L), 100L, "OPEN", now));

        PromotionAuctionDecision lower = adapter.decide(command("cmd-2", "hash-2", 42L, 110L), 100L, "OPEN", now.plusMillis(1));
        PromotionAuctionDecision equal = adapter.decide(command("cmd-3", "hash-3", 42L, 120L), 100L, "OPEN", now.plusMillis(2));

        assertThat(lower.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        assertThat(equal.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
    }

    private PromotionAuctionCommand command(String commandId, String hash, long bidder, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, 301L, 201L + bidder, bidder, 1000L + bidder,
                "FEED_TOP_SLOT", amount, "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @ImportAutoConfiguration({RedisAutoConfiguration.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PromotionRedisDecisionAdapter adapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
            return new PromotionRedisDecisionAdapter(redisTemplate, objectMapper);
        }
    }
}
