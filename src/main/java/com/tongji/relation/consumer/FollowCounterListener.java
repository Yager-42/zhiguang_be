package com.tongji.relation.consumer;

import com.tongji.counter.service.UserCounterService;
import com.tongji.relation.manager.FollowCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 关注关系提交后的进程内计数监听器。
 *
 * <p>在关系事务提交后同步执行（跑在请求线程），对 Redis SDS 计数做增量，
 * 一次 Lua 完成关注数与粉丝数两个字段的 INCRBY。Redis 故障时只记录日志不重抛、
 * 不重试——请求已在事务提交时成功，计数自愈交给读侧 300s 采样校验与 follow_graph 对账兜底。</p>
 */
@Component
public class FollowCounterListener {

    private static final Logger log = LoggerFactory.getLogger(FollowCounterListener.class);

    private final UserCounterService userCounterService;

    public FollowCounterListener(UserCounterService userCounterService) {
        this.userCounterService = userCounterService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowCommitted(FollowCommittedEvent event) {
        try {
            userCounterService.incrementFollowings(event.fromUserId(), event.delta());
            userCounterService.incrementFollowers(event.toUserId(), event.delta());
        } catch (Exception ex) {
            log.warn("follow counter listener failed from={} to={} delta={}, deferred to read-verify/reconcile",
                    event.fromUserId(), event.toUserId(), event.delta(), ex);
        }
    }
}