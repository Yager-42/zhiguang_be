package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = PromotionRedisDecisionAdapterRedisIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.database=15"
})
@EnabledIf("redisReachable")
class PromotionRedisDecisionAdapterRedisIntegrationTest {

    private static final long WINDOW_ID = 301L;
    private static final String PREFIX = "promotion:auction:{301}";

    @Autowired private StringRedisTemplate redis;
    @Autowired private PromotionRedisDecisionAdapter adapter;
    @Autowired private PromotionAuctionHotStateRepository hotStateRepository;
    @Autowired private PromotionRedisWindowCloser windowCloser;
    @Autowired private PromotionRedisSnapshotAdapter snapshotAdapter;

    @BeforeEach
    void cleanUp() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void acceptReplayConflictAndRejectPreserveSingleStreamEvent() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);

        PromotionAuctionDecision accepted = adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L));
        PromotionAuctionDecision replay = adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L));
        PromotionAuctionDecision conflict = adapter.decide(bid("cmd-1", "hash-2", 243L, 42L, 120L));
        PromotionAuctionDecision belowReserve = adapter.decide(bid("cmd-2", "hash-3", 243L, 42L, 90L));

        assertThat(accepted.accepted()).isTrue();
        assertThat(replay.decisionId()).isEqualTo(accepted.decisionId());
        assertThat(conflict.rejectionReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(belowReserve.rejectionReason()).isEqualTo("BELOW_RESERVE");
        assertThat(belowReserve.decisionVersion()).isEqualTo(1L);
        assertThat(streamIds()).containsExactly("1-0");
        // T4：幂等改为单 key Hash + 字段级 TTL（HSETEX），key 本身无 TTL。
        Set<String> commandKeys = redis.keys(PREFIX + ":commands");
        assertThat(commandKeys).hasSize(1);
        assertThat(redis.opsForHash().hasKey(commandKeys.iterator().next(), "cmd-1")).isTrue();
    }

    @Test
    void concurrentEqualBidsProduceExactlyOneOrderedEventEach() throws Exception {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        authorize(244L, 43L, 500L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L)));
            var second = executor.submit(() -> adapter.decide(bid("cmd-2", "hash-2", 244L, 43L, 120L)));

            PromotionAuctionDecision firstDecision = first.get();
            PromotionAuctionDecision secondDecision = second.get();
            assertThat(firstDecision.accepted()).isTrue();
            assertThat(secondDecision.accepted()).isTrue();
            PromotionAuctionDecision earliest = firstDecision.decisionVersion() == 1L
                    ? firstDecision : secondDecision;
            assertThat(snapshotAdapter.snapshot(WINDOW_ID).ranking().getFirst().campaignId())
                    .isEqualTo(String.valueOf(earliest.campaignId()));
        }

        assertThat(streamIds()).containsExactly("1-0", "2-0");
        assertThat(redis.opsForHash().get(PREFIX + ":state", "decisionVersion")).isEqualTo("2");
    }

    @Test
    void monotonicBidAndAuthorizationRulesRejectWithoutAdvancingVersion() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 120L);
        adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L));

        PromotionAuctionDecision aboveAuthorization =
                adapter.decide(bid("cmd-2", "hash-2", 243L, 42L, 130L));
        PromotionAuctionDecision equal =
                adapter.decide(bid("cmd-3", "hash-3", 243L, 42L, 120L));

        assertThat(aboveAuthorization.rejectionReason()).isEqualTo("ESCROW_INSUFFICIENT");
        assertThat(equal.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void streamVersionMismatchPausesAuctionBeforeAnyWrite() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        redis.opsForHash().put(PREFIX + ":state", "decisionVersion", "1");

        assertThatThrownBy(() -> adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L)))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("REDIS_STREAM_VERSION_MISMATCH");
        assertThat(streamIds()).isEmpty();
    }

    @Test
    void wrongRedisKeyTypePausesAuction() {
        redis.opsForValue().set(PREFIX + ":state", "wrong-type");

        assertThatThrownBy(() -> adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L)))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("REDIS_KEY_TYPE_MISMATCH");
    }

    @Test
    void closeEventWinsBeforeAnyLaterBidAndUsesNextExplicitVersion() {
        initialize(Instant.now().minusSeconds(1));
        authorize(243L, 42L, 500L);

        PromotionAuctionDecision close = windowCloser.close(WINDOW_ID).orElseThrow();
        PromotionAuctionDecision rejected = adapter.decide(bid("cmd-1", "hash-1", 243L, 42L, 120L));

        assertThat(close.type()).isEqualTo("WINDOW_CLOSED");
        assertThat(rejected.rejectionReason()).isEqualTo("WINDOW_CLOSED");
        assertThat(rejected.decisionVersion()).isEqualTo(close.decisionVersion());
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void bidVersusCloseRaceCannotAcceptAfterRedisTimeBoundary() throws Exception {
        initialize(Instant.now().minusSeconds(1));
        authorize(243L, 42L, 500L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var bidResult = executor.submit(
                    () -> adapter.decide(bid("cmd-race", "hash-race", 243L, 42L, 120L)));
            var closeResult = executor.submit(() -> windowCloser.close(WINDOW_ID).orElseThrow());

            assertThat(bidResult.get().rejectionReason()).isEqualTo("WINDOW_CLOSED");
            assertThat(closeResult.get().type()).isEqualTo("WINDOW_CLOSED");
        }
        assertThat(streamIds()).containsExactly("1-0");
    }

    private void initialize(Instant windowEndAt) {
        hotStateRepository.initialize(route(0L, 0L, windowEndAt), 0L);
    }

    private void authorize(long campaignId, long bidderUserId, long amount) {
        hotStateRepository.projectAuthorization(route(campaignId, bidderUserId, Instant.now().plusSeconds(60), amount));
    }

    private PromotionBidRoute route(long campaignId, long bidderUserId, Instant endAt) {
        return route(campaignId, bidderUserId, endAt, 0L);
    }

    private PromotionBidRoute route(long campaignId, long bidderUserId, Instant endAt, long authorizedAmount) {
        return new PromotionBidRoute(campaignId, bidderUserId, 1000L + bidderUserId, WINDOW_ID,
                "FEED_TOP_SLOT", 100L, authorizedAmount, "OPEN", endAt, 2, "REDIS_STREAM");
    }

    private PromotionAuctionCommand bid(
            String commandId, String hash, long campaignId, long bidderUserId, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, WINDOW_ID, campaignId, bidderUserId,
                1000L + bidderUserId, "FEED_TOP_SLOT", amount, 100L, "OPEN", "BID", Instant.now());
    }

    private List<String> streamIds() {
        var records = redis.opsForStream().range(PREFIX + ":events", Range.unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(record -> record.getId().getValue()).toList();
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
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean PromotionBPrimeProperties promotionBPrimeProperties() {
            return new PromotionBPrimeProperties();
        }

        @Bean PromotionRedisDecisionAdapter adapter(
                StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                PromotionBPrimeProperties properties) {
            return new PromotionRedisDecisionAdapter(redisTemplate, objectMapper, properties);
        }

        @Bean PromotionAuctionHotStateRepository hotStateRepository(
                StringRedisTemplate redisTemplate, PromotionBPrimeProperties properties) {
            return new PromotionAuctionHotStateRepository(redisTemplate, properties);
        }

        @Bean PromotionRedisWindowCloser windowCloser(
                StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                PromotionBPrimeProperties properties) {
            return new PromotionRedisWindowCloser(redisTemplate, objectMapper, properties);
        }

        @Bean PromotionRedisSnapshotAdapter snapshotAdapter(
                StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
            return new PromotionRedisSnapshotAdapter(redisTemplate, objectMapper);
        }
    }
}
