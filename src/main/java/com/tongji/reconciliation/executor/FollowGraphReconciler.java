package com.tongji.reconciliation.executor;

import com.tongji.counter.service.UserCounterService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.mapper.RelationMapper.RelationRepairRow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FollowGraphReconciler implements Reconciler {

    private final RelationMapper relationMapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    public FollowGraphReconciler(RelationMapper relationMapper,
                                 StringRedisTemplate redis,
                                 UserCounterService userCounterService) {
        this.relationMapper = relationMapper;
        this.redis = redis;
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
        long userId = task.getTargetId();
        rebuildFollowingSide(userId);
        rebuildFollowerSide(userId);
        userCounterService.rebuildAllCounters(userId);
    }

    private void rebuildFollowingSide(long userId) {
        List<RelationRepairRow> rows = relationMapper.listActiveFollowingRowsByUser(userId);
        reconcileFollowerMirror(userId, rows);
        String key = "uf:flws:" + userId;
        redis.delete(key);
        for (RelationRepairRow row : rows) {
            relationMapper.insertFollower(row.id(), row.toUserId(), row.fromUserId(), 1);
            long score = row.createdAt() == null ? System.currentTimeMillis() : row.createdAt().getTime();
            redis.opsForZSet().add(key, String.valueOf(row.toUserId()), score);
        }
        redis.expire(key, Duration.ofHours(2));
    }

    private void reconcileFollowerMirror(long userId, List<RelationRepairRow> followingRows) {
        Set<Long> activeTargets = new HashSet<>();
        for (RelationRepairRow row : followingRows) {
            activeTargets.add(row.toUserId());
        }
        for (RelationRepairRow followerRow : relationMapper.listActiveFollowerRowsBySourceUser(userId)) {
            if (!activeTargets.contains(followerRow.toUserId())) {
                relationMapper.cancelFollower(followerRow.toUserId(), userId);
            }
        }
    }

    private void rebuildFollowerSide(long userId) {
        List<RelationRepairRow> rows = relationMapper.listActiveFollowerRowsByUser(userId);
        String key = "uf:fans:" + userId;
        redis.delete(key);
        for (RelationRepairRow row : rows) {
            long score = row.createdAt() == null ? System.currentTimeMillis() : row.createdAt().getTime();
            redis.opsForZSet().add(key, String.valueOf(row.fromUserId()), score);
        }
        redis.expire(key, Duration.ofHours(2));
    }
}
