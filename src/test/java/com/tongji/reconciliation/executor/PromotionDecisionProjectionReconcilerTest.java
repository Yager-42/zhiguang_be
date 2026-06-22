package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionRecord;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionDecisionProjectionReconcilerTest {
    @Test
    void replaysDecisionPayloadThroughProjectionService() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        when(decisionMapper.findByDecisionId("101")).thenReturn(PromotionAuctionDecisionRecord.builder()
                .decisionId("101")
                .payloadJson("""
                        {"decisionId":"101","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                        "campaignId":201,"bidderUserId":42,"postId":1001,"resourceType":"FEED_TOP_SLOT",
                        "decisionType":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                        "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                        """)
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build());
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                decisionMapper, projectionService, new ObjectMapper().findAndRegisterModules());

        reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                .targetId(101L)
                .build());

        verify(projectionService).project(any());
    }

    @Test
    void replaysDecisionPayloadFromTaskPayloadWhenProjectionRowIsMissing() {
        PromotionAuctionDecisionMapper decisionMapper = mock(PromotionAuctionDecisionMapper.class);
        PromotionDecisionProjectionService projectionService = mock(PromotionDecisionProjectionService.class);
        PromotionDecisionProjectionReconciler reconciler = new PromotionDecisionProjectionReconciler(
                decisionMapper, projectionService, new ObjectMapper().findAndRegisterModules());

        reconciler.reconcile(ReconciliationTask.builder()
                .targetType(ReconciliationTargetType.PROMOTION_DECISION)
                .targetId(101L)
                .taskPayload("""
                        {"decisionId":"101","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                        "campaignId":201,"bidderUserId":42,"postId":1001,"resourceType":"FEED_TOP_SLOT",
                        "decisionType":"BID_ACCEPTED","accepted":true,"rejectionReason":null,"bidAmount":120,
                        "ranking":[],"walletEffects":[],"decidedAt":"2026-06-20T10:05:00Z"}
                        """)
                .build());

        verify(projectionService).project(any());
    }
}
