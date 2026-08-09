package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PromotionRedisDecisionAdapterRedisIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.database=0"
})
@EnabledIf("redisReachable")
class PromotionRedisDecisionAdapterRedisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-20T10:05:00Z");
    private static final String PREFIX = "promotion:auction:{301}";

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PromotionRedisDecisionAdapter adapter;

    @BeforeEach
    void cleanUp() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void escrowAuthorizationEnablesAtomicAcceptReplayAndConflictDecision() {
        authorize("escrow-1", 243L, 42L, 500L);

        PromotionAuctionDecision accepted = adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L), NOW);
        PromotionAuctionDecision replay = adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L), NOW);
        PromotionAuctionDecision conflict = adapter.decide(bid("cmd-1", "hash-2", 243L, 42L, 120L), NOW);
        PromotionAuctionDecision belowReserve = adapter.decide(bid("cmd-2", "hash-3", 243L, 42L, 90L), NOW);

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.walletEffects()).isEmpty();
        assertThat(replay.decisionId()).isEqualTo(accepted.decisionId());
        assertThat(conflict.rejectionReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(belowReserve.rejectionReason()).isEqualTo("BELOW_RESERVE");
        assertThat(redis.opsForHash().get(PREFIX + ":escrow", "243:currentHold")).isEqualTo("120");
        var expirations = redis.opsForHash().getTimeToLive(PREFIX + ":commands", TimeUnit.SECONDS,
                List.of("cmd-1:hash", "cmd-1:decision"));
        assertThat(expirations.ttlOf("cmd-1:hash").getSeconds()).isPositive();
        assertThat(expirations.ttlOf("cmd-1:decision").getSeconds()).isPositive();
    }

    @Test
    void acceptedDecisionUpdatesRankingAndCampaignInsideTheDecisionScript() {
        authorize("escrow-1", 243L, 42L, 500L);
        authorize("escrow-2", 244L, 43L, 500L);
        adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L), NOW);

        PromotionAuctionDecision decision = adapter.decide(
                bid("cmd-2", "hash-2", 244L, 43L, 130L), NOW.plusMillis(1));

        assertThat(decision.ranking()).isEmpty();
        assertThat(redis.opsForZSet().reverseRange(PREFIX + ":ranking", 0, 29))
                .containsExactly("244", "243");
        assertThat(redis.opsForHash().get(PREFIX + ":campaign:243", "bidAmount")).isEqualTo("120");
    }

    @Test
    void rejectsBidAboveAuthorizationAndLowerOrEqualRebid() {
        authorize("escrow-1", 243L, 42L, 120L);
        adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L), NOW);

        PromotionAuctionDecision aboveAuthorization = adapter.decide(
                bid("cmd-2", "hash-2", 243L, 42L, 130L), NOW.plusMillis(1));
        PromotionAuctionDecision equal = adapter.decide(
                bid("cmd-3", "hash-3", 243L, 42L, 120L), NOW.plusMillis(2));

        assertThat(aboveAuthorization.rejectionReason()).isEqualTo("ESCROW_INSUFFICIENT");
        assertThat(equal.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
    }

    private PromotionAuctionDecision authorize(String commandId, long campaignId, long bidder, long amount) {
        PromotionAuctionCommand command = new PromotionAuctionCommand(commandId, "escrow:" + amount,
                "hash:" + commandId, 301L, campaignId, bidder, 1000L + bidder, "FEED_TOP_SLOT", amount,
                100L, "OPEN", "ESCROW_NOTIFY", NOW);
        return adapter.decide(command, NOW);
    }

    private PromotionAuctionCommand bid(String commandId, String hash, long campaignId, long bidder, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, 301L, campaignId, bidder, 1000L + bidder,
                "FEED_TOP_SLOT", amount, 100L, "OPEN", "BID", NOW);
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
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PromotionBPrimeProperties promotionBPrimeProperties() {
            return new PromotionBPrimeProperties();
        }

        @Bean
        PromotionRedisDecisionAdapter adapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                              PromotionBPrimeProperties properties) {
            return new PromotionRedisDecisionAdapter(redisTemplate, objectMapper, properties);
        }
    }
}
