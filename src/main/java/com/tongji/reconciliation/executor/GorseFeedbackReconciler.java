package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.recommendation.gorse.GorseClient;
import org.springframework.stereotype.Component;

@Component
public class GorseFeedbackReconciler implements Reconciler {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;

    public GorseFeedbackReconciler(ObjectMapper objectMapper, GorseClient gorseClient) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.GORSE_FEEDBACK;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        GorseFeedbackPayload payload = readPayload(task);
        gorseClient.insertFeedback(payload.feedbackType(), payload.userId(), payload.itemId());
    }

    private GorseFeedbackPayload readPayload(ReconciliationTask task) {
        if (task.getTaskPayload() == null || task.getTaskPayload().isBlank()) {
            throw new IllegalStateException("gorse_feedback task " + task.getId() + " missing payload");
        }
        try {
            return objectMapper.readValue(task.getTaskPayload(), GorseFeedbackPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid gorse_feedback payload for task " + task.getId(), e);
        }
    }

    public record GorseFeedbackPayload(
            String feedbackType,
            long userId,
            String itemId
    ) {
    }
}
