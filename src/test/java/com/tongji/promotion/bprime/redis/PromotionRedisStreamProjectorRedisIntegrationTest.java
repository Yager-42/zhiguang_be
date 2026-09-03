package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.service.PromotionAuctionHotStateLifecycle;
import com.tongji.promotion.bprime.service.PromotionDecisionFanoutService;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.promotion.bprime.service.PromotionDecisionStreamConsumer;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@EnabledIf("redisReachable")
class PromotionRedisStreamProjectorRedisIntegrationTest {

    private static final long WINDOW_ID = 901L;
    private static final String PREFIX = "promotion:auction:{901}";

    @Mock private PromotionProjectionCheckpointMapper checkpointMapper;
    @Mock private PromotionDecisionProjectionService projectionService;
    @Mock private PromotionDecisionFanoutService fanoutService;
    @Mock private PromotionPerformanceMetrics metrics;
    @Mock private PromotionAuctionHotStateLifecycle hotStateLifecycle;
    @Mock private PromotionAuctionDeadlineManager deadlineManager;
    @Mock private PromotionAuctionAvailabilityGate availabilityGate;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;
    private PromotionRedisStreamProjector projector;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.setDatabase(14);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setStreamReadBatchSize(1000);
        PromotionDecisionStreamConsumer consumer = new PromotionDecisionStreamConsumer(
                redis, objectMapper, checkpointMapper, projectionService, fanoutService, metrics, properties,
                new PromotionBidAdmissionState(properties), hotStateLifecycle, deadlineManager);
        projector = new PromotionRedisStreamProjector(redis, consumer, hotStateLifecycle, availabilityGate);
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        // sweep 只遍历 active-streams 注册表：显式登记测试窗口（与生产初始化流程一致）
        redis.opsForSet().add(PromotionAuctionRedisKeys.activeStreams(), String.valueOf(WINDOW_ID));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void sweepRecoversEventWhenPubSubWakeupWasLost() throws Exception {
        when(availabilityGate.allowsProjection()).thenReturn(true);

        redis.opsForValue().set(PromotionAuctionRedisKeys.publicationWakeup(WINDOW_ID), "1");
        append(decision("d-1", 1L, 0L), "1-0");
        projectInputAsResult();

        projector.sweep();

        ArgumentCaptor<List<PromotionDecisionProjectionItem>> items = listCaptor();
        verify(projectionService).projectBatch(items.capture());
        assertThat(items.getValue()).extracting(PromotionDecisionProjectionItem::streamId)
                .containsExactly("1-0");
        verify(fanoutService).publishDecision(items.getValue().getFirst().decision());
        assertThat(redis.hasKey(PromotionAuctionRedisKeys.publicationWakeup(WINDOW_ID))).isFalse();
        verify(deadlineManager, never()).cancel(WINDOW_ID);
    }

    @Test
    void restartResumesStrictlyAfterMysqlCheckpoint() throws Exception {
        when(availabilityGate.allowsProjection()).thenReturn(true);

        append(decision("d-1", 1L, 0L), "1-0");
        append(decision("d-2", 2L, 1L), "2-0");
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(WINDOW_ID);
        checkpoint.setLastDecisionId("d-1");
        checkpoint.setLastDecisionVersion(1L);
        checkpoint.setLastStreamId("1-0");
        when(checkpointMapper.findByAuctionWindowId(WINDOW_ID)).thenReturn(checkpoint);
        projectInputAsResult();

        projector.processWindow(WINDOW_ID);

        ArgumentCaptor<List<PromotionDecisionProjectionItem>> items = listCaptor();
        verify(projectionService).projectBatch(items.capture());
        assertThat(items.getValue()).singleElement()
                .extracting(PromotionDecisionProjectionItem::streamId)
                .isEqualTo("2-0");
    }

    @Test
    void forgedPubSubMessageCannotCreateAnEvent() {
        byte[] channel = (PREFIX + ":pub").getBytes(StandardCharsets.UTF_8);
        projector.onMessage(new DefaultMessage(
                "999-0".getBytes(StandardCharsets.UTF_8), channel), null);

        verify(projectionService, never()).projectBatch(anyList());
        verify(fanoutService, never()).publishDecision(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void settledHotStateExpiresOnlyAfterCheckpointCatchesLatestStreamVersion() throws Exception {
        when(availabilityGate.allowsProjection()).thenReturn(true);

        append(decision("d-1", 1L, 0L), "1-0");
        redis.opsForHash().put(PREFIX + ":state", "status", "CLOSED");
        redis.opsForHash().put(PREFIX + ":escrow", "_initialized", "1");
        redis.opsForZSet().add(PREFIX + ":ranking", "201", -120D);
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(WINDOW_ID);
        checkpoint.setLastDecisionId("d-1");
        checkpoint.setLastDecisionVersion(1L);
        checkpoint.setLastStreamId("1-0");
        when(checkpointMapper.findByAuctionWindowId(WINDOW_ID)).thenReturn(checkpoint);

        projector.processWindow(WINDOW_ID);

        verify(hotStateLifecycle).retireSettledState(WINDOW_ID);
    }

    @Test
    void soldTerminalCancelsDeadlineAfterProjectionAndBeforeHotStateRetirement() throws Exception {
        verifyTerminalCancellation("AUCTION_SOLD", Map.of(
                "winnerCampaignId", "201",
                "winningAmount", 120L,
                "actualEndAtEpochMs", 1_750_419_600_000L,
                "finalWindowStatus", "SETTLED"));
    }

    @Test
    void noBidTerminalCancelsDeadlineAfterProjectionAndBeforeHotStateRetirement() throws Exception {
        verifyTerminalCancellation("AUCTION_NO_BID", Map.of(
                "actualEndAtEpochMs", 1_750_419_600_000L,
                "finalWindowStatus", "SETTLED"));
    }

    private void verifyTerminalCancellation(String decisionType, Map<String, Object> payload) throws Exception {
        when(availabilityGate.allowsProjection()).thenReturn(true);

        PromotionAuctionDecision terminal = new PromotionAuctionDecision(
                "d-terminal", "cmd-terminal", "hash", WINDOW_ID, 1L, 0L,
                "AUCTION_SOLD".equals(decisionType) ? 201L : 0L,
                "AUCTION_SOLD".equals(decisionType) ? 42L : 0L,
                "AUCTION_SOLD".equals(decisionType) ? 1001L : 0L,
                "FEED_TOP_SLOT", decisionType, true, null, 120L,
                List.of(), List.of(), payload, Instant.parse("2026-06-20T11:00:00Z"));
        append(terminal, "1-0");
        PromotionProjectionCheckpointRecord checkpoint = new PromotionProjectionCheckpointRecord();
        checkpoint.setAuctionWindowId(WINDOW_ID);
        checkpoint.setLastDecisionId("d-terminal");
        checkpoint.setLastDecisionVersion(1L);
        checkpoint.setLastStreamId("1-0");
        when(checkpointMapper.findByAuctionWindowId(WINDOW_ID)).thenReturn(null, checkpoint);
        projectInputAsResult();

        projector.processWindow(WINDOW_ID);
        assertThat(redis.opsForSet().isMember(
                PromotionAuctionRedisKeys.activeStreams(), String.valueOf(WINDOW_ID))).isTrue();

        InOrder ordered = inOrder(projectionService, deadlineManager, hotStateLifecycle);
        ordered.verify(projectionService).projectBatch(anyList());
        ordered.verify(deadlineManager).cancel(WINDOW_ID);
        ordered.verify(hotStateLifecycle).retireSettledState(WINDOW_ID);
    }

    private void append(PromotionAuctionDecision decision, String streamId) throws Exception {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText("return redis.call('XADD', KEYS[1], ARGV[1], 'decision', ARGV[2])");
        script.setResultType(String.class);
        redis.execute(script, List.of(PREFIX + ":events"), streamId,
                objectMapper.writeValueAsString(decision));
    }

    private PromotionAuctionDecision decision(String id, long version, long previousVersion) {
        return new PromotionAuctionDecision(
                id, "cmd-" + version, "hash", WINDOW_ID, version, previousVersion,
                201L + version, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true, null,
                120L + version, List.of(), List.of(), Map.of(), Instant.now());
    }

    private void projectInputAsResult() {
        when(projectionService.projectBatch(anyList())).thenAnswer(invocation ->
                invocation.<List<PromotionDecisionProjectionItem>>getArgument(0).stream()
                        .map(PromotionDecisionProjectionItem::decision)
                        .toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<PromotionDecisionProjectionItem>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
