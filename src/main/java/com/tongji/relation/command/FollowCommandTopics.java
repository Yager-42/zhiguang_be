package com.tongji.relation.command;

/**
 * 关注命令主题常量。
 */
public final class FollowCommandTopics {

    /** 关注/取关命令主题（key=fromUserId，保证同一发起者的命令有序） */
    public static final String COMMAND = "relation-command";

    /** 消费失败重试耗尽后的死信主题 */
    public static final String DLT = "relation-command-dlt";

    private FollowCommandTopics() {
    }
}
