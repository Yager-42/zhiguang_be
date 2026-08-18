package com.tongji.relation.command;

/**
 * 关注/取关命令事件（生产端投递 Kafka，消费端执行关系事务）。
 *
 * <p>生产端只做幂等短路 + 限流 + 投递；消费端复用 {@link com.tongji.relation.manager.RelationManager}
 * 的现有事务链路（幂等门控 + AFTER_COMMIT 计数监听器），故重投/乱序由消息侧键分区与
 * 关系表唯一键天然兜底。</p>
 *
 * @param fromUserId 发起者
 * @param toUserId 目标者
 * @param follow true=关注，false=取关
 */
public record FollowCommandEvent(long fromUserId, long toUserId, boolean follow) {
}