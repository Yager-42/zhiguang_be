package com.tongji.reconciliation.executor;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.storage.text.TextStorageService;
import org.springframework.stereotype.Component;

@Component
public class CassandraTextReconciler implements Reconciler {

    private final KnowPostMapper knowPostMapper;
    private final TextStorageService textStorageService;

    public CassandraTextReconciler(KnowPostMapper knowPostMapper, TextStorageService textStorageService) {
        this.knowPostMapper = knowPostMapper;
        this.textStorageService = textStorageService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.CASSANDRA_TEXT;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (ReconciliationTargetType.POST.equals(task.getTargetType())) {
            reconcilePost(task.getTargetId());
            return;
        }
        if (ReconciliationTargetType.COMMENT.equals(task.getTargetType())) {
            throw new IllegalStateException(
                    "Comment " + task.getTargetId()
                            + " cannot be repaired: no durable source body is stored for reconciliation"
            );
        }
        throw new IllegalStateException("cassandra_text unsupported target type " + task.getTargetType());
    }

    private void reconcilePost(Long postId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null) {
            throw new IllegalStateException("Post " + postId + " not found for cassandra_text reconciliation");
        }
        String body = textStorageService.getPostText(postId, row.getContentUrl())
                .orElseThrow(() -> new IllegalStateException("No source body available for post " + postId));
        textStorageService.savePostText(postId, body, row.getContentSha256());
    }
}
