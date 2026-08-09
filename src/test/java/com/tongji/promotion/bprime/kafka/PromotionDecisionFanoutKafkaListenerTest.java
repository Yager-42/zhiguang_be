package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.service.PromotionDecisionFanoutService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionDecisionFanoutKafkaListenerTest {

    @Test
    void fanoutConsumesEnvelopeAndAcknowledges() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionDecisionFanoutService fanoutService = mock(PromotionDecisionFanoutService.class);
        PromotionPerformanceMetrics performanceMetrics = mock(PromotionPerformanceMetrics.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        PromotionDecisionFanoutKafkaListener listener = new PromotionDecisionFanoutKafkaListener(
                objectMapper, new PromotionDecisionKafkaSupport(), fanoutService, performanceMetrics);
        PromotionAuctionDecision decision = decision();
        when(fanoutService.publishDecision(decision)).thenReturn(true);
        String payload = objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                decision, PromotionDecisionHasher.hash(decision), Instant.parse("2026-06-20T10:05:01Z")));

        listener.onMessage(new ConsumerRecord<>("decisions.v2", 0, 7L, "301", payload), ack);

        verify(fanoutService).publishDecision(decision);
        verify(performanceMetrics).recordRealtimeComplete(decision);
        verify(ack).acknowledge();
    }

    @Test
    void rejectsRecordWhenKafkaKeyDoesNotMatchAuctionWindowId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionDecisionFanoutService fanoutService = mock(PromotionDecisionFanoutService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        PromotionDecisionFanoutKafkaListener listener = new PromotionDecisionFanoutKafkaListener(
                objectMapper, new PromotionDecisionKafkaSupport(), fanoutService,
                mock(PromotionPerformanceMetrics.class));
        PromotionAuctionDecision decision = decision();
        String payload = objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                decision, PromotionDecisionHasher.hash(decision), Instant.parse("2026-06-20T10:05:01Z")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        listener.onMessage(new ConsumerRecord<>("decisions.v2", 0, 7L, "999", payload), ack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key must match auctionWindowId");

        verify(fanoutService, never()).publishDecision(org.mockito.ArgumentMatchers.any());
        verify(ack, never()).acknowledge();
    }

    private PromotionAuctionDecision decision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 2L, 1L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(), List.of(), Map.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
