package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 窗口有序决策：Redis 原子判定后，Kafka durable ACK 成功才允许 RocketMQ listener 返回。 */
@Service
public class PromotionCommandProcessingService {

    private final PromotionRedisDecisionAdapter redisDecisionAdapter;
    private final PromotionDecisionLogPort decisionLogPort;
    private final PromotionPerformanceMetrics performanceMetrics;
    private final PromotionBidFastRejectFilter fastRejectFilter;

    public PromotionCommandProcessingService(PromotionRedisDecisionAdapter redisDecisionAdapter,
                                             PromotionDecisionLogPort decisionLogPort,
                                             PromotionPerformanceMetrics performanceMetrics,
                                             PromotionBidFastRejectFilter fastRejectFilter) {
        this.redisDecisionAdapter = redisDecisionAdapter;
        this.decisionLogPort = decisionLogPort;
        this.performanceMetrics = performanceMetrics;
        this.fastRejectFilter = fastRejectFilter;
    }

    /**
     * 处理单条命令，并在 Kafka durable ACK 完成后返回。
     *
     * @param command 已按窗口路由的竞价命令，不允许为 {@code null}
     */
    public void process(PromotionAuctionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        processBatch(List.of(command));
    }

    /**
     * 按 RocketMQ 队列顺序完成 Redis 裁决，再以有界批次等待 Kafka durable ACK。
     *
     * <p>任一 append 失败时整个 RocketMQ 批次重投；Redis commandId 幂等结果保证重放不会重复扣减授权。</p>
     *
     * @param commands 同一 RocketMQ 有序消费批次，数量由 consumer 配置限制
     */
    public void processBatch(List<PromotionAuctionCommand> commands) {
        Objects.requireNonNull(commands, "commands must not be null");
        if (commands.isEmpty()) {
            return;
        }
        List<PromotionAuctionDecision> decisions = new ArrayList<>(commands.size());
        for (PromotionAuctionCommand command : commands) {
            Objects.requireNonNull(command, "commands must not contain null");
            PromotionAuctionDecision decision = redisDecisionAdapter.decide(command, Instant.now());
            fastRejectFilter.observeDecision(decision);
            decisions.add(decision);
        }
        decisionLogPort.appendBatch(decisions);
        decisions.forEach(performanceMetrics::recordDecisionDurable);
    }
}
