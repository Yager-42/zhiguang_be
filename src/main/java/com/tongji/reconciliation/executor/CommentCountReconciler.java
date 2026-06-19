package com.tongji.reconciliation.executor;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.counter.service.CounterService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommentCountReconciler implements Reconciler {

    private final CommentMapper commentMapper;
    private final CounterService counterService;

    public CommentCountReconciler(CommentMapper commentMapper, CounterService counterService) {
        this.commentMapper = commentMapper;
        this.counterService = counterService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.COMMENT_COUNT;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (ReconciliationTargetType.POST.equals(task.getTargetType())) {
            int count = commentMapper.countActiveTopLevelByPost(task.getTargetId());
            counterService.overwriteCount("knowpost", String.valueOf(task.getTargetId()), "comment", count);
            return;
        }
        if (ReconciliationTargetType.COMMENT.equals(task.getTargetType())) {
            int replyCount = commentMapper.countActiveRepliesByRoot(task.getTargetId());
            commentMapper.updateReplyCount(task.getTargetId(), replyCount);
            counterService.overwriteCount("comment", String.valueOf(task.getTargetId()), "comment", replyCount);
            return;
        }
        throw new IllegalStateException("comment_count unsupported target type " + task.getTargetType());
    }
}
