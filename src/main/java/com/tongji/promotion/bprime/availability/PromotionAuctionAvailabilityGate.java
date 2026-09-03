package com.tongji.promotion.bprime.availability;

/**
 * bprime 外部收单的进程内可用性闸门。
 */
public interface PromotionAuctionAvailabilityGate {

    /**
     * 在入口建立任何竞价或保证金状态前确认本进程已完成完整启动恢复。
     */
    void requireAvailable();

    /**
     * 启动围栏、Stream 注册表恢复和 deadline 恢复全部成功后仅允许调用一次。
     */
    void acceptAfterStartupRecovery();

    /**
     * 发生不可恢复的本地激活或 deadline 注册失败后永久拒绝当前进程的收单流量。
     */
    void pause();

    /**
     * 启动围栏完成后允许继续消费和结算既有 Redis Stream；运行期暂停收单不撤销此许可。
     *
     * @return 启动围栏是否已成功完成
     */
    boolean allowsProjection();

    /**
     * @return 当前进程是否接受新的 bprime 收单流量
     */
    boolean isAvailable();
}
