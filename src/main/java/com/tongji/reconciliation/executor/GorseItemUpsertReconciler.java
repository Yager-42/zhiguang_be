package com.tongji.reconciliation.executor;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseItemInputFactory;
import org.springframework.stereotype.Component;

@Component
public class GorseItemUpsertReconciler implements Reconciler {

    private final KnowPostMapper knowPostMapper;
    private final GorseClient gorseClient;
    private final GorseItemInputFactory itemInputFactory;

    public GorseItemUpsertReconciler(KnowPostMapper knowPostMapper,
                                     GorseClient gorseClient,
                                     GorseItemInputFactory itemInputFactory) {
        this.knowPostMapper = knowPostMapper;
        this.gorseClient = gorseClient;
        this.itemInputFactory = itemInputFactory;
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
        KnowPost post = knowPostMapper.findById(task.getTargetId());
        if (post == null) {
            throw new IllegalStateException("Post " + task.getTargetId() + " not found for gorse item upsert");
        }
        if (!"published".equalsIgnoreCase(post.getStatus())
                || post.getCreatorId() == null
                || post.getPublishTime() == null) {
            throw new IllegalStateException("Post " + task.getTargetId() + " is not eligible for gorse item upsert");
        }
        gorseClient.upsertItem(itemInputFactory.from(post));
    }
}
