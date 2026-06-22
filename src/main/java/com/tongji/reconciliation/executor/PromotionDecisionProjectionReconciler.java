package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionRecord;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

@Component
public class PromotionDecisionProjectionReconciler implements Reconciler {

    private final PromotionAuctionDecisionMapper decisionMapper;
    private final PromotionDecisionProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public PromotionDecisionProjectionReconciler(PromotionAuctionDecisionMapper decisionMapper,
                                                 PromotionDecisionProjectionService projectionService,
                                                 ObjectMapper objectMapper) {
        this.decisionMapper = decisionMapper;
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.PROMOTION_DECISION_PROJECTION;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.PROMOTION_DECISION.equals(task.getTargetType())) {
            throw new IllegalStateException("promotion_decision_projection only supports promotion_decision target");
        }
        try {
            if (task.getTaskPayload() != null && task.getTaskPayload().trim().startsWith("{")) {
                projectionService.project(objectMapper.readValue(task.getTaskPayload(), PromotionAuctionDecision.class));
                return;
            }
            String decisionId = task.getTaskPayload() == null || task.getTaskPayload().isBlank()
                    ? String.valueOf(task.getTargetId())
                    : task.getTaskPayload();
            PromotionAuctionDecisionRecord record = decisionMapper.findByDecisionId(decisionId);
            if (record == null) {
                throw new IllegalStateException("promotion decision not found: " + decisionId);
            }
            projectionService.project(objectMapper.readValue(record.getPayloadJson(), PromotionAuctionDecision.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to replay promotion decision projection", e);
        }
    }
}
