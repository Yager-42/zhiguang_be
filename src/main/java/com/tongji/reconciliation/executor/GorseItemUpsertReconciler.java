package com.tongji.reconciliation.executor;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.recommendation.gorse.GorseClient;
import org.springframework.stereotype.Component;

@Component
public class GorseItemUpsertReconciler implements Reconciler {

    private final KnowPostMapper knowPostMapper;
    private final GorseClient gorseClient;

    public GorseItemUpsertReconciler(KnowPostMapper knowPostMapper, GorseClient gorseClient) {
        this.knowPostMapper = knowPostMapper;
        this.gorseClient = gorseClient;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.GORSE_ITEM_UPSERT;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.POST.equals(task.getTargetType())) {
            throw new IllegalStateException("gorse_item_upsert only supports post target, got " + task.getTargetType());
        }
        KnowPostDetailRow row = knowPostMapper.findDetailById(task.getTargetId());
        if (row == null) {
            throw new IllegalStateException("Post " + task.getTargetId() + " not found for gorse item upsert");
        }
        if (!"published".equalsIgnoreCase(row.getStatus()) || row.getCreatorId() == null || row.getPublishTime() == null) {
            throw new IllegalStateException("Post " + task.getTargetId() + " is not eligible for gorse item upsert");
        }
        gorseClient.upsertItem(task.getTargetId(), row.getCreatorId(), row.getPublishTime());
    }
}
