package com.tongji.relation.manager;

/**
 * 关注关系事实提交后的事件（由关系事务提交监听器消费）。
 *
 * <p>在 {@link RelationManagerImpl#follow(long, long)} / unfollow 事务内发布，
 * 仅当 {@code afterCommit} 后由监听器投递。delta = +1 关注 / -1 取关。</p>
 *
 * @param fromUserId 发起者
 * @param toUserId 目标者
 * @param delta 计数增量（+1 关注，-1 取关）
 */
public record FollowCommittedEvent(long fromUserId, long toUserId, int delta) {
}