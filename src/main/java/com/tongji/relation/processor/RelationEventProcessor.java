package com.tongji.relation.processor;

import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.tongji.counter.service.UserCounterService;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * 关系事件处理器。
 * 职责：对 FollowCreated/FollowCanceled 事件进行去重与幂等处理，落库更新粉丝表，维护关注/粉丝 ZSet 缓存与 TTL，并基于事实刷新用户维度计数（SDS）。
 *
 * <p>幂等与一致性设计（修复计数不一致问题的关键约束）：</p>
 * <ul>
 *   <li>去重键按 outbox 事件 ID 唯一，且仅在效果全部落地成功后写入。失败重投会重新执行，
 *       所有效果（粉丝镜像 upsert、ZSet 增删、计数）均为幂等操作，重放安全；
 *       不会出现“去重键已占位但效果未执行”导致事件被永久丢弃。</li>
 *   <li>粉丝镜像与 ZSet 写入前先以 following 事实为准（{@link RelationMapper#existsFollowing}）
 *       做防护：乱序到达的过期事件（例如取消事件晚于重新关注事件）直接跳过，
 *       最终状态收敛到当前事实，不依赖事件到达顺序。</li>
 *   <li>关注数/粉丝数改为基于事实派生（{@link UserCounterService#rebuildFollowCounters}），
 *       不再使用增量累加；天然免疫去重、乱序与部分执行，任何一次事件落地都会把计数收敛到数据库事实。</li>
 * </ul>
 */
@Service
public class RelationEventProcessor {
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);
    private static final Duration ZSET_TTL = Duration.ofHours(2);

    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    public RelationEventProcessor(RelationMapper mapper, StringRedisTemplate redis, UserCounterService userCounterService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    /**
     * 处理关系事件（无 outbox 事件 ID 的兜底入口，去重键退化为按类型+用户对）。
     * @param evt 关系事件
     */
    public void process(RelationEvent evt) {
        process(evt, null);
    }

    /**
     * 处理关系事件。
     * @param evt 关系事件
     * @param outboxEventId outbox 行 ID（唯一，用于精确去重；为空时按类型+用户对去重）
     */
    public void process(RelationEvent evt, Long outboxEventId) {
        if (evt.fromUserId() == null || evt.toUserId() == null) {
            return;
        }
        if (!"FollowCreated".equals(evt.type()) && !"FollowCanceled".equals(evt.type())) {
            return;
        }
        String dedupKey = dedupKey(evt, outboxEventId);
        if (Boolean.TRUE.equals(redis.hasKey(dedupKey))) {
            // 已成功落地过，跳过（幂等）
            return;
        }

        // 以 following 事实为准：只有当前事实成立才允许镜像写入，乱序的过期事件在此被过滤
        boolean currentlyFollowing = mapper.existsFollowing(evt.fromUserId(), evt.toUserId()) > 0;
        if ("FollowCreated".equals(evt.type())) {
            if (currentlyFollowing) {
                // 异步插入粉丝表（幂等 upsert）
                mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);
                long now = System.currentTimeMillis();

                // 更新关注表与粉丝表缓存：ZSet 按时间分数维护最近项，设置短 TTL 减少陈旧数据
                redis.opsForZSet().add("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()), now);
                redis.opsForZSet().add("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()), now);
                redis.expire("uf:flws:" + evt.fromUserId(), ZSET_TTL);
                redis.expire("uf:fans:" + evt.toUserId(), ZSET_TTL);
            }
        } else if ("FollowCanceled".equals(evt.type())) {
            if (!currentlyFollowing) {
                mapper.cancelFollower(evt.toUserId(), evt.fromUserId());

                // 更新关注表与粉丝表缓存：移除 ZSet 项并刷新 TTL
                redis.opsForZSet().remove("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()));
                redis.opsForZSet().remove("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()));
                redis.expire("uf:flws:" + evt.fromUserId(), ZSET_TTL);
                redis.expire("uf:fans:" + evt.toUserId(), ZSET_TTL);
            }
        }

        // 关注数/粉丝数按事实派生：即使本次事件被防护跳过，计数也会收敛到当前数据库事实
        userCounterService.rebuildFollowCounters(evt.fromUserId(), evt.toUserId());

        // 效果全部落地成功后才打去重标记；失败时异常上抛、不 ack，重投会重新执行
        redis.opsForValue().set(dedupKey, "1", DEDUP_TTL);
    }

    private String dedupKey(RelationEvent evt, Long outboxEventId) {
        if (outboxEventId != null) {
            return "dedup:rel:" + outboxEventId;
        }
        // 兜底路径（无 outbox ID）：按类型+用户对去重
        return "dedup:rel:" + evt.type() + ":" + evt.fromUserId() + ":" + evt.toUserId();
    }
}
