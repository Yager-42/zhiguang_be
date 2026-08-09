package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** 将权威竞价结果追加到 durable Decision Log。 */
public interface PromotionDecisionLogPort {

    /**
     * 追加单条结果，并在 durable ACK 前保持阻塞。
     *
     * @param decision 已由 Redis 原子裁决的结果，不允许为 {@code null}
     */
    void append(PromotionAuctionDecision decision);

    /**
     * 异步追加单条结果；完成信号只表示 broker 已满足 durable ACK 配置。
     *
     * @param decision 已由 Redis 原子裁决的结果，不允许为 {@code null}
     * @return durable ACK 完成阶段，不返回 {@code null}
     */
    CompletionStage<Void> appendAsync(PromotionAuctionDecision decision);

    /**
     * 有界并行追加同一 RocketMQ 消费批次，并等待批内所有 durable ACK。
     *
     * @param decisions 保持 RocketMQ 投递顺序的结果列表，不允许为 {@code null}
     */
    void appendBatch(List<PromotionAuctionDecision> decisions);
}
