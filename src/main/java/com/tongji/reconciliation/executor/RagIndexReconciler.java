package com.tongji.reconciliation.executor;

import com.tongji.llm.rag.RagIndexService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

@Component
public class RagIndexReconciler implements Reconciler {

    private final RagIndexService ragIndexService;

    public RagIndexReconciler(RagIndexService ragIndexService) {
        this.ragIndexService = ragIndexService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.RAG_INDEX;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.POST.equals(task.getTargetType())) {
            throw new IllegalStateException("rag_index only supports post target, got " + task.getTargetType());
        }
        ragIndexService.ensureIndexedStrict(task.getTargetId());
    }
}
