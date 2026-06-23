package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PromotionDecisionProjectionKafkaListenerTest {
    @Test
    void projectsAndAcknowledges() throws Exception {
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        PromotionDecisionProjectionKafkaListener listener = new PromotionDecisionProjectionKafkaListener(
                new ObjectMapper().findAndRegisterModules(), projectionService);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionAuctionDecision decision = new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 2L, 1L,
                201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L,
                List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("decisions.v2", 3, 99L, "301",
                objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                        decision, PromotionDecisionHasher.hash(decision), Instant.parse("2026-06-20T10:05:01Z"))));

        listener.onMessage(record, ack);

        verify(projectionService).project(any(), eq("decisions.v2"), eq(3), eq(99L));
        verify(ack).acknowledge();
    }

    @Test
    void rejectsRecordWhenKafkaKeyDoesNotMatchAuctionWindowId() throws Exception {
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionDecisionProjectionKafkaListener listener = new PromotionDecisionProjectionKafkaListener(
                objectMapper, projectionService, new PromotionDecisionKafkaSupport());
        PromotionAuctionDecision decision = new PromotionAuctionDecision("d-1", "cmd-1", "hash",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 120L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("decisions.v2", 3, 99L, "999",
                objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                        decision, PromotionDecisionHasher.hash(decision),
                        Instant.parse("2026-06-20T10:05:01Z"))));

        assertThatThrownBy(() -> listener.onMessage(record, ack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key must match auctionWindowId");

        verify(projectionService, never()).project(any(), any(), any(), any());
        verify(ack, never()).acknowledge();
    }
}
