package com.tongji.relation.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关注/取关事实写（消费端与内部事务入口）。
 *
 * <p>请求线程不再直接调用本类：命令由 {@link com.tongji.relation.command.RelationCommandService}
 * 投递 Kafka 后由消费端在这里执行事务（幂等门控 + AFTER_COMMIT 计数监听器）。</p>
 */
@Service
public class RelationManagerImpl implements RelationManager {

    private final RelationMapper relationMapper;
    private final IdService idService;
    private final RelationPublisher relationPublisher;
    private final ResilienceGuard resilienceGuard;
    private final ApplicationEventPublisher applicationEventPublisher;

    public RelationManagerImpl(RelationMapper relationMapper,
                               IdService idService,
                               RelationPublisher relationPublisher,
                               ResilienceGuard resilienceGuard,
                               ApplicationEventPublisher applicationEventPublisher) {
        this.relationMapper = relationMapper;
        this.idService = idService;
        this.relationPublisher = relationPublisher;
        this.resilienceGuard = resilienceGuard;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public RelationWriteResult follow(long fromUserId, long toUserId) {
        if (isFollowing(fromUserId, toUserId)) {
            return RelationWriteResult.unchanged(true);
        }

        long relationId = idService.nextId(IdNamespace.RELATION);
        int changed = relationMapper.insertFollowing(relationId, fromUserId, toUserId, 1);
        if (changed != 1) {
            return RelationWriteResult.unchanged(true);
        }

        Long persistedRelationId = relationMapper.findActiveFollowingId(fromUserId, toUserId);
        if (persistedRelationId == null) {
            throw new IllegalStateException("Active following row not found after follow write");
        }

        applicationEventPublisher.publishEvent(new FollowCommittedEvent(fromUserId, toUserId, 1));

        GuardResult<Void> publishResult = resilienceGuard.execute(
                "relation:outbox-publish",
                () -> {
                    relationPublisher.publishFollowCreated(fromUserId, toUserId, persistedRelationId);
                    return null;
                },
                () -> null,
                this::isSystemFailure
        );
        requireCriticalGuardSuccess(publishResult);
        return RelationWriteResult.changed(true);
    }

    @Override
    @Transactional
    public RelationWriteResult unfollow(long fromUserId, long toUserId) {
        if (!isFollowing(fromUserId, toUserId)) {
            return RelationWriteResult.unchanged(false);
        }

        int changed = relationMapper.cancelFollowing(fromUserId, toUserId);
        if (changed <= 0) {
            return RelationWriteResult.unchanged(false);
        }

        applicationEventPublisher.publishEvent(new FollowCommittedEvent(fromUserId, toUserId, -1));
        GuardResult<Void> publishResult = resilienceGuard.execute(
                "relation:outbox-publish",
                () -> {
                    relationPublisher.publishFollowCanceled(fromUserId, toUserId);
                    return null;
                },
                () -> null,
                this::isSystemFailure
        );
        requireCriticalGuardSuccess(publishResult);
        return RelationWriteResult.changed(false);
    }

    @Override
    public Map<String, Boolean> status(long userId, long otherUserId) {
        boolean following = isFollowing(userId, otherUserId);
        boolean followedBy = isFollowing(otherUserId, userId);
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.put("following", following);
        result.put("followedBy", followedBy);
        result.put("mutual", following && followedBy);
        return result;
    }

    @Override
    public boolean isFollowing(long fromUserId, long toUserId) {
        return relationMapper.existsFollowing(fromUserId, toUserId) > 0;
    }

    private boolean isSystemFailure(Throwable throwable) {
        return !(throwable instanceof BusinessException);
    }

    private void requireCriticalGuardSuccess(GuardResult<Void> guardResult) {
        if (!guardResult.fallbackApplied()) {
            return;
        }
        Throwable failure = guardResult.failure();
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("relation outbox publish degraded", failure);
    }
}