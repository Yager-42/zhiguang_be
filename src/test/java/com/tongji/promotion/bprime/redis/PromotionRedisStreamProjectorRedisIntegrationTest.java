package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.bprime.service.PromotionDecisionFanoutService;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@EnabledIf("redisReachable")
class PromotionRedisStreamProjectorRedisIntegrationTest {

    private static final long WINDOW_ID = 901L;
    private static final String PREFIX = "promotion:auction:{901}";

    @Mock private PromotionProjectionCheckpointMapper checkpointMapper;
    @Mock private PromotionAuctionWindowMapper windowMapper;
    @Mock private PromotionDecisionProjectionService projectionService;
    @Mock private PromotionDecisionFanoutService fanoutService;
    @Mock private PromotionPerformanceMetrics metrics;

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
        projector = new PromotionRedisStreamProjector(
                redis, objectMapper, checkpointMapper, windowMapper, projectionService,
                fanoutService, metrics, properties, new PromotionBidPriceCache(properties));
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void sweepRecoversEventWhenPubSubWakeupWasLost() throws Exception {
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
    }

    @Test
    void restartResumesStrictlyAfterMysqlCheckpoint() throws Exception {
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
        when(windowMapper.findById(WINDOW_ID)).thenReturn(PromotionAuctionWindow.builder()
                .id(WINDOW_ID)
                .status(PromotionAuctionWindowStatus.SETTLED)
                .build());

        projector.processWindow(WINDOW_ID);

        assertThat(redis.opsForHash().get(PREFIX + ":state", "status")).isEqualTo("SETTLED");
        assertThat(redis.getExpire(PREFIX + ":state")).isPositive();
        assertThat(redis.getExpire(PREFIX + ":events")).isPositive();
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
