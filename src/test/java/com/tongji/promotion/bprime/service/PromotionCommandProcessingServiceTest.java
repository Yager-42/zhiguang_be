package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionCommandProcessingServiceTest {

    private PromotionRedisDecisionAdapter redisDecisionAdapter;
    private PromotionDecisionLogPort decisionLogPort;
    private PromotionPerformanceMetrics performanceMetrics;
    private PromotionBidFastRejectFilter fastRejectFilter;
    private PromotionCommandProcessingService service;

    @BeforeEach
    void setUp() {
        redisDecisionAdapter = mock(PromotionRedisDecisionAdapter.class);
        decisionLogPort = mock(PromotionDecisionLogPort.class);
        performanceMetrics = mock(PromotionPerformanceMetrics.class);
        fastRejectFilter = mock(PromotionBidFastRejectFilter.class);
        service = new PromotionCommandProcessingService(redisDecisionAdapter, decisionLogPort, performanceMetrics,
                fastRejectFilter);
    }

    @Test
    void appendsRedisDecisionBatchBeforeRecordingDurability() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = decision();
        when(redisDecisionAdapter.decide(eq(command), any())).thenReturn(decision);

        service.process(command);

        InOrder ordered = inOrder(redisDecisionAdapter, fastRejectFilter, decisionLogPort, performanceMetrics);
        ordered.verify(redisDecisionAdapter).decide(eq(command), any());
        ordered.verify(fastRejectFilter).observeDecision(decision);
        ordered.verify(decisionLogPort).appendBatch(List.of(decision));
        ordered.verify(performanceMetrics).recordDecisionDurable(decision);
    }

    @Test
    void kafkaFailurePropagatesSoRocketMqDoesNotAck() {
        PromotionAuctionCommand command = command();
        PromotionAuctionDecision decision = decision();
        when(redisDecisionAdapter.decide(eq(command), any())).thenReturn(decision);
        doThrow(new IllegalStateException("kafka unavailable"))
                .when(decisionLogPort).appendBatch(List.of(decision));

        assertThatThrownBy(() -> service.process(command)).isInstanceOf(IllegalStateException.class);

        verify(fastRejectFilter).observeDecision(decision);
        verify(performanceMetrics, never()).recordDecisionDurable(any());
    }

    private PromotionAuctionCommand command() {
        return new PromotionAuctionCommand("cmd-1", "idem-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", 120L, 100L, "OPEN", "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }

    private PromotionAuctionDecision decision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
