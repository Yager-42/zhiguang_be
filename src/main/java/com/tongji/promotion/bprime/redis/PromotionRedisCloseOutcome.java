package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;

import java.util.Objects;

/**
 * 表达 Redis 权威关窗脚本的一次完整裁决结果。
 *
 * <p>结果只描述当前调用观察到的终态或剩余时间，不承担调度和重试职责。</p>
 *
 * @since 2026-09-03
 */
public sealed interface PromotionRedisCloseOutcome permits PromotionRedisCloseOutcome.Closed,
        PromotionRedisCloseOutcome.NotDue, PromotionRedisCloseOutcome.AlreadyTerminal {

    /**
     * Redis 已原子写入或重放终态决策。
     *
     * @param decision AUCTION_SOLD 或 AUCTION_NO_BID 权威决策
     */
    record Closed(PromotionAuctionDecision decision) implements PromotionRedisCloseOutcome {
        public Closed {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * Redis 权威时间尚未到达窗口固定 deadline。
     *
     * @param redisNowEpochMs Redis 当前时间，Unix epoch 毫秒
     * @param deadlineEpochMs 窗口固定 deadline，Unix epoch 毫秒
     */
    record NotDue(long redisNowEpochMs, long deadlineEpochMs) implements PromotionRedisCloseOutcome {
    }

    /** Redis 状态已经由其他原子裁决进入终态。 */
    record AlreadyTerminal() implements PromotionRedisCloseOutcome {
    }
}
