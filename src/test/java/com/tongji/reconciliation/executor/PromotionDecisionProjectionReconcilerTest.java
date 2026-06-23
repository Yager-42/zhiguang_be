package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PromotionDecisionProjectionReconcilerTest {

    @Test
    void rejectsRawDecisionPayloadBecauseReplayRequiresKafkaEnvelope() {
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                projectionService, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> reconciler.reconcile(ReconciliationTask.builder()
                    .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                    .targetId(101L)
                    .taskPayload("""
                            {"decisionId":"101","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                            "campaignId":201,"bidderUserId":42,"postId":1001,"resourceType":"FEED_TOP_SLOT",
                            "decisionType":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                            "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                            """)
                    .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to replay promotion decision projection");

        verify(projectionService, never()).project(any());
    }

    @Test
    void replaysKafkaEnvelopePayloadFromTaskPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                projectionService, objectMapper);
        PromotionAuctionDecision decision = new PromotionAuctionDecision("101", "cmd-1", "hash",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 120L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));

        reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                .targetId(101L)
                .taskPayload(objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                        decision, PromotionDecisionHasher.hash(decision),
                        Instant.parse("2026-06-20T10:05:01Z"))))
                .build());

        verify(projectionService).project(any());
    }

    @Test
    void rejectsKafkaEnvelopeWithBadHashInsteadOfProjectingForgedDecision() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                projectionService, objectMapper);
        PromotionAuctionDecision decision = new PromotionAuctionDecision("101", "cmd-1", "hash",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 120L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));

        assertThatThrownBy(() -> reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                .targetId(101L)
                .taskPayload(objectMapper.writeValueAsString(PromotionAuctionDecisionLogEnvelope.auctionDecision(
                        decision, "forged-hash", Instant.parse("2026-06-20T10:05:01Z"))))
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to replay promotion decision projection");

        verify(projectionService, never()).project(any());
    }

    @Test
    void rejectsMissingPayloadBecauseMysqlDecisionTableIsRemoved() {
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                mock(PromotionDecisionProjectionService.class), new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                .targetId(101L)
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to replay promotion decision projection");
    }
}
