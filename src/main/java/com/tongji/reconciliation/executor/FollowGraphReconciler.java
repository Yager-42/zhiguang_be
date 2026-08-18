package com.tongji.reconciliation.executor;

import com.tongji.counter.service.UserCounterService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

/**
 * 关注图谱对账器。
 *
 * <p>镜像表与 uf:* ZSet 缓存已随计数链路坍缩删除，关注/粉丝计数由事务提交监听器增量维护，
 * 读侧采样校验兜底；本对账器仅保留基于数据库事实的全量计数重建，作为增量链路之外的离线纠偏。</p>
 */
@Component
public class FollowGraphReconciler implements Reconciler {

    private final UserCounterService userCounterService;

    public FollowGraphReconciler(UserCounterService userCounterService) {
        this.userCounterService = userCounterService;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.FOLLOW_GRAPH;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        if (!ReconciliationTargetType.USER.equals(task.getTargetType())) {
            throw new IllegalStateException("follow_graph only supports user target, got " + task.getTargetType());
        }
        userCounterService.rebuildAllCounters(task.getTargetId());
    }
}