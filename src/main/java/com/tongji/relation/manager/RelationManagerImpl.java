package com.tongji.relation.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RelationManagerImpl implements RelationManager {

    private static final String TOKEN_BUCKET_LUA = """
            
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = redis.call('TIME')[1]
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            if not last then last = now; tokens = capacity end
            local elapsed = tonumber(now) - tonumber(last)
            local add = elapsed * rate
            tokens = math.min(capacity, tonumber(tokens) + add)
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            tokens = tokens - 1
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            redis.call('PEXPIRE', key, 60000)
            return 1
            """;

    private final RelationMapper relationMapper;
    private final StringRedisTemplate redisTemplate;
    private final IdService idService;
    private final RelationPublisher relationPublisher;
    private final ResilienceGuard resilienceGuard;
    private final DefaultRedisScript<Long> tokenScript;

    public RelationManagerImpl(RelationMapper relationMapper,
                               StringRedisTemplate redisTemplate,
                               IdService idService,
                               RelationPublisher relationPublisher,
                               ResilienceGuard resilienceGuard) {
        this.relationMapper = relationMapper;
        this.redisTemplate = redisTemplate;
        this.idService = idService;
        this.relationPublisher = relationPublisher;
        this.resilienceGuard = resilienceGuard;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
    }

    @Override
    @Transactional
    public RelationWriteResult follow(long fromUserId, long toUserId) {
        if (isFollowing(fromUserId, toUserId)) {
            return RelationWriteResult.unchanged(true);
        }

        Long allowed = redisTemplate.execute(tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
        if (!Long.valueOf(1L).equals(allowed)) {
            return RelationWriteResult.failed();
        }

        long relationId = idService.nextId(IdNamespace.RELATION);
        int changed = relationMapper.insertFollowing(relationId, fromUserId, toUserId, 1);
        if (changed <= 0) {
            return RelationWriteResult.unchanged(true);
        }

        Long persistedRelationId = relationMapper.findActiveFollowingId(fromUserId, toUserId);
        if (persistedRelationId == null) {
            throw new IllegalStateException("Active following row not found after follow write");
        }

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

    private boolean isFollowing(long fromUserId, long toUserId) {
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
