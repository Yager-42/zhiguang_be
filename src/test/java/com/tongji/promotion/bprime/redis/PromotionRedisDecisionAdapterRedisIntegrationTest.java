package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandBatch;
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

/**
 * 英式升价语义集成测试：共享价格台阶 required=current+increment（reserve=100、increment=100），
 * BID_NOT_HIGHER 拒绝带 requiredAmount，固定 deadline 的终态为 AUCTION_SOLD/AUCTION_NO_BID，
 * cap-hit 后出价返回 WINDOW_CLOSED。
 */
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
    @Autowired private ObjectMapper objectMapper;
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

        PromotionAuctionDecision accepted = decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));
        PromotionAuctionDecision replay = decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));
        PromotionAuctionDecision conflict = decide(bid("cmd-1", "hash-2", 243L, 42L, 200L));
        PromotionAuctionDecision belowLadder = decide(bid("cmd-2", "hash-3", 243L, 42L, 250L));

        assertThat(accepted.accepted()).isTrue();
        assertThat(replay.decisionId()).isEqualTo(accepted.decisionId());
        assertThat(conflict.rejectionReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(belowLadder.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        assertThat(belowLadder.payload().get("requiredAmount")).isEqualTo(300L);
        assertThat(belowLadder.payload().get("currentPriceCents")).isEqualTo(200L);
        assertThat(belowLadder.decisionVersion()).isEqualTo(1L);
        assertThat(streamIds()).containsExactly("1-0");
        assertThat(redis.hasKey(PREFIX + ":commands")).isFalse();
        assertThat(redis.opsForHash().get(PREFIX + ":state", "winnerCommandId")).isEqualTo("cmd-1");
    }

    @Test
    void concurrentBidsOverSharedLadderProduceExactlyOneAcceptance() throws Exception {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        authorize(244L, 43L, 500L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> decide(bid("cmd-1", "hash-1", 243L, 42L, 200L)));
            var second = executor.submit(() -> decide(bid("cmd-2", "hash-2", 244L, 43L, 210L)));

            PromotionAuctionDecision firstDecision = first.get();
            PromotionAuctionDecision secondDecision = second.get();
            // 共享价格台阶：先到者接受，后到者 required=先者+100 → 拒绝。
            PromotionAuctionDecision accepted = firstDecision.accepted() ? firstDecision : secondDecision;
            PromotionAuctionDecision rejected = firstDecision.accepted() ? secondDecision : firstDecision;
            assertThat(accepted.accepted()).isTrue();
            assertThat(rejected.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
            assertThat(snapshotAdapter.snapshot(WINDOW_ID).ranking().getFirst().campaignId())
                    .isEqualTo(String.valueOf(accepted.campaignId()));
            assertThat(snapshotAdapter.snapshot(WINDOW_ID).currentPriceCents())
                    .isEqualTo(accepted.bidAmount());
        }

        assertThat(streamIds()).containsExactly("1-0");
        assertThat(redis.opsForHash().get(PREFIX + ":state", "decisionVersion")).isEqualTo("1");
    }

    @Test
    void oneBatchAcceptsHighestValidCandidateOnly() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        authorize(244L, 43L, 250L);
        authorize(245L, 44L, 500L);
        List<PromotionAuctionCommand> commands = List.of(
                bid("cmd-high-invalid", "hash-high", 244L, 43L, 400L),
                bid("cmd-winner", "hash-winner", 245L, 44L, 350L),
                bid("cmd-low", "hash-low", 243L, 42L, 200L));

        var result = adapter.decide(new PromotionAuctionCommandBatch(WINDOW_ID, commands));

        assertThat(result.items()).extracting(item -> item.outcome().name())
                .containsExactly("BID_NOT_HIGHER", "ACCEPTED", "BID_NOT_HIGHER");
        assertThat(result.committedPriceCents()).isEqualTo(350L);
        assertThat(result.winnerCommandId()).isEqualTo("cmd-winner");
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void replacingWinnerInSameBatchMakesOldWinnerReplayNotHigher() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        authorize(244L, 43L, 500L);
        PromotionAuctionCommand oldWinner = bid("cmd-old", "hash-old", 243L, 42L, 200L);
        assertThat(decide(oldWinner).accepted()).isTrue();
        PromotionAuctionCommand higher = bid("cmd-new", "hash-new", 244L, 43L, 350L);

        var result = adapter.decide(new PromotionAuctionCommandBatch(WINDOW_ID, List.of(higher, oldWinner)));

        assertThat(result.items()).extracting(item -> item.outcome().name())
                .containsExactly("ACCEPTED", "BID_NOT_HIGHER");
        assertThat(streamIds()).containsExactly("1-0", "2-0");
    }

    @Test
    void escrowAndLadderRulesRejectWithoutAdvancingVersion() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 240L);
        decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));

        PromotionAuctionDecision aboveLadder =
                decide(bid("cmd-2", "hash-2", 243L, 42L, 250L));
        PromotionAuctionDecision equal =
                decide(bid("cmd-3", "hash-3", 243L, 42L, 200L));

        assertThat(aboveLadder.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        assertThat(equal.rejectionReason()).isEqualTo("BID_NOT_HIGHER");
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void escrowInsufficientRejectsWhenLadderClearedButAuthorizationShort() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 190L);

        PromotionAuctionDecision insufficient =
                decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));

        assertThat(insufficient.rejectionReason()).isEqualTo("ESCROW_INSUFFICIENT");
        assertThat(streamIds()).isEmpty();
    }

    @Test
    void streamVersionMismatchPausesAuctionBeforeAnyWrite() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        redis.opsForHash().put(PREFIX + ":state", "decisionVersion", "1");

        assertThatThrownBy(() -> decide(bid("cmd-1", "hash-1", 243L, 42L, 200L)))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("REDIS_STREAM_VERSION_MISMATCH");
        assertThat(streamIds()).isEmpty();
    }

    @Test
    void wrongRedisKeyTypePausesAuction() {
        redis.opsForValue().set(PREFIX + ":state", "wrong-type");

        assertThatThrownBy(() -> decide(bid("cmd-1", "hash-1", 243L, 42L, 200L)))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("REDIS_KEY_TYPE_MISMATCH");
    }

    @Test
    void closeWithoutBidTerminatesNoBidAndLaterBidGetsWindowClosed() {
        initialize(Instant.now().minusSeconds(1));
        authorize(243L, 42L, 500L);

        PromotionAuctionDecision close = closeDecision();
        PromotionAuctionDecision rejected = decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));

        assertThat(close.type()).isEqualTo("AUCTION_NO_BID");
        assertThat(rejected.rejectionReason()).isEqualTo("WINDOW_CLOSED");
        assertThat(rejected.decisionVersion()).isEqualTo(close.decisionVersion());
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void closeAfterBidTerminatesSoldWithWinnerAndWinningAmount() {
        initialize(Instant.now().plusSeconds(60));
        authorize(243L, 42L, 500L);
        PromotionAuctionDecision accepted =
                decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));
        assertThat(accepted.accepted()).isTrue();
        // 本用例只验证到期有赢家关窗，直接把固定 deadline 设置到 Redis 过去时间。
        redis.opsForHash().put(PREFIX + ":state", "windowEndAtEpochMs", "1");

        PromotionAuctionDecision close = closeDecision();

        assertThat(close.type()).isEqualTo("AUCTION_SOLD");
        assertThat(close.payload()).containsEntry("winnerCampaignId", "243");
        assertThat(close.payload().get("winningAmount")).isEqualTo(200);
    }

    @Test
    void capHitTerminatesSoldAndLaterBidGetsWindowClosed() {
        initialize(Instant.now().plusSeconds(60), 100L, 300L);
        authorize(243L, 42L, 500L);

        PromotionAuctionDecision capBid = decide(bid("cmd-1", "hash-1", 243L, 42L, 300L));
        PromotionAuctionDecision afterSold = decide(bid("cmd-2", "hash-2", 243L, 42L, 300L));

        assertThat(capBid.accepted()).isTrue();
        assertThat(capBid.type()).isEqualTo("BID_ACCEPTED");
        assertThat(streamDecisionTypes()).containsExactly("BID_ACCEPTED", "AUCTION_SOLD");
        assertThat(redis.opsForHash().get(PREFIX + ":state", "status")).isEqualTo("SOLD");
        assertThat(afterSold.rejectionReason()).isEqualTo("WINDOW_CLOSED");
    }

    @Test
    void bidVersusCloseRaceCannotAcceptAfterRedisTimeBoundary() throws Exception {
        initialize(Instant.now().minusSeconds(1));
        authorize(243L, 42L, 500L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var bidResult = executor.submit(
                    () -> decide(bid("cmd-race", "hash-race", 243L, 42L, 200L)));
            var closeResult = executor.submit(this::closeDecision);

            assertThat(bidResult.get().rejectionReason()).isEqualTo("WINDOW_CLOSED");
            assertThat(closeResult.get().type()).isEqualTo("AUCTION_NO_BID");
        }
        assertThat(streamIds()).containsExactly("1-0");
    }

    @Test
    void nearDeadlineBidDoesNotChangeFixedDeadline() {
        initialize(Instant.now().plusSeconds(5));
        authorize(243L, 42L, 500L);
        Object fixedDeadline = redis.opsForHash().get(PREFIX + ":state", "windowEndAtEpochMs");

        PromotionAuctionDecision accepted = decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));

        assertThat(accepted.accepted()).isTrue();
        assertThat(redis.opsForHash().get(PREFIX + ":state", "windowEndAtEpochMs"))
                .isEqualTo(fixedDeadline);
        assertThat(streamDecisionTypes()).containsExactly("BID_ACCEPTED");
    }

    @Test
    void bidAfterDeadlineLeavesOpenStateForDeadlineCloser() {
        initialize(Instant.now().minusSeconds(1));
        authorize(243L, 42L, 500L);

        PromotionAuctionDecision rejected = decide(bid("cmd-1", "hash-1", 243L, 42L, 200L));

        assertThat(rejected.rejectionReason()).isEqualTo("WINDOW_CLOSED");
        assertThat(redis.opsForHash().get(PREFIX + ":state", "status")).isEqualTo("OPEN");
        assertThat(streamIds()).isEmpty();
    }

    @Test
    void capHitThenCloseReturnsAlreadyTerminal() {
        initialize(Instant.now().plusSeconds(60), 100L, 300L);
        authorize(243L, 42L, 500L);
        decide(bid("cmd-1", "hash-1", 243L, 42L, 300L));

        assertThat(windowCloser.close(WINDOW_ID))
                .isEqualTo(new PromotionRedisCloseOutcome.AlreadyTerminal());
    }

    private void initialize(Instant windowEndAt) {
        initialize(windowEndAt, 100L, 0L);
    }

    private void initialize(Instant windowEndAt, long reservePrice, long capPriceCents) {
        hotStateRepository.initialize(route(0L, 0L, windowEndAt, 0L, reservePrice, capPriceCents), 0L);
    }

    private void authorize(long campaignId, long bidderUserId, long amount) {
        hotStateRepository.projectAuthorization(
                route(campaignId, bidderUserId, Instant.now().plusSeconds(60), amount, 100L, 0L));
    }

    private PromotionBidRoute route(long campaignId, long bidderUserId, Instant endAt,
                                    long authorizedAmount, long reservePrice, long capPriceCents) {
        return new PromotionBidRoute(campaignId, bidderUserId, 1000L + bidderUserId, WINDOW_ID,
                "FEED_TOP_SLOT", reservePrice, authorizedAmount, "OPEN", endAt, 2,
                100L, capPriceCents);
    }

    private PromotionAuctionCommand bid(
            String commandId, String hash, long campaignId, long bidderUserId, long amount) {
        return new PromotionAuctionCommand(commandId, "idem", hash, WINDOW_ID, campaignId, bidderUserId,
                1000L + bidderUserId, "FEED_TOP_SLOT", amount, 100L, "OPEN", "BID", Instant.now());
    }

    private PromotionAuctionDecision closeDecision() {
        PromotionRedisCloseOutcome outcome = windowCloser.close(WINDOW_ID);
        assertThat(outcome).isInstanceOf(PromotionRedisCloseOutcome.Closed.class);
        return ((PromotionRedisCloseOutcome.Closed) outcome).decision();
    }

    private PromotionAuctionDecision decide(PromotionAuctionCommand command) {
        return adapter.decide(new PromotionAuctionCommandBatch(WINDOW_ID, List.of(command)))
                .items().getFirst().decision();
    }

    private List<String> streamIds() {
        var records = redis.opsForStream().range(PREFIX + ":events", Range.unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(record -> record.getId().getValue()).toList();
    }

    private List<String> streamDecisionTypes() {
        var records = redis.opsForStream().range(PREFIX + ":events", Range.unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(record -> {
            try {
                return objectMapper.readTree(String.valueOf(record.getValue().get("decision")))
                        .path("type").asText();
            } catch (JsonProcessingException exception) {
                throw new AssertionError("invalid Redis Stream decision JSON", exception);
            }
        }).toList();
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
