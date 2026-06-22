package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionDecisionProjectionKafkaListenerTest {
    @Test
    void projectsAndAcknowledges() throws Exception {
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        PromotionDecisionProjectionKafkaListener listener = new PromotionDecisionProjectionKafkaListener(
                new ObjectMapper().findAndRegisterModules(), projectionService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("decisions.v2", 3, 99L, "301", """
                {"decisionId":"d-1","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                "campaignId":201,"bidderUserId":42,"postId":1001,"resourceType":"FEED_TOP_SLOT",
                "decisionType":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                """);

        listener.onMessage(record, ack);

        verify(projectionService).project(any(), eq("decisions.v2"), eq(3), eq(99L));
        verify(ack).acknowledge();
    }
}
