package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.kafka.PromotionDecisionKafkaSupport;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PromotionDecisionProjectionReconciler implements Reconciler {

    private final PromotionDecisionProjectionService projectionService;
    private final ObjectMapper objectMapper;
    private final PromotionDecisionKafkaSupport support;

    @Autowired
    public PromotionDecisionProjectionReconciler(PromotionDecisionProjectionService projectionService,
                                                 ObjectMapper objectMapper) {
        this(projectionService, objectMapper, new PromotionDecisionKafkaSupport());
    }

    public PromotionDecisionProjectionReconciler(PromotionDecisionProjectionService projectionService,
                                                 ObjectMapper objectMapper,
                                                 PromotionDecisionKafkaSupport support) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
        this.support = support;
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
                projectionService.project(readDecision(task.getTaskPayload()));
                return;
            }
            throw new IllegalStateException("promotion decision replay requires Kafka decision payload");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to replay promotion decision projection", e);
        }
    }

    private PromotionAuctionDecision readDecision(String payload) throws Exception {
        if (payload.contains("\"eventType\"")) {
            return support.requireDecision(objectMapper.readValue(payload, PromotionAuctionDecisionLogEnvelope.class));
        }
        throw new IllegalStateException("promotion decision replay requires Kafka decision envelope");
    }
}
