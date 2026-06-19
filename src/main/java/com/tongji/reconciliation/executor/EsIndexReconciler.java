package com.tongji.reconciliation.executor;

import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.counter.service.CounterService;
import com.tongji.search.index.SearchIndexService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EsIndexReconciler implements Reconciler {

    private final CounterService counterService;
    private final SearchIndexService searchIndexService;

    public EsIndexReconciler(CounterService counterService, SearchIndexService searchIndexService) {
        this.counterService = counterService;
        this.searchIndexService = searchIndexService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.ES_INDEX;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.POST.equals(task.getTargetType())) {
            throw new IllegalStateException("es_index only supports post target, got " + task.getTargetType());
        }
        counterService.rebuildCountsFromFacts("knowpost", String.valueOf(task.getTargetId()), List.of("like", "fav"));
        searchIndexService.upsertKnowPostStrict(task.getTargetId());
    }
}
