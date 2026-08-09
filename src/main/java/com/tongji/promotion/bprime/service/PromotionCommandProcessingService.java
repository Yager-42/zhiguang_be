package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import org.springframework.stereotype.Service;

import java.time.Instant;

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

    public void process(PromotionAuctionCommand command) {
        PromotionAuctionDecision decision = redisDecisionAdapter.decide(command, Instant.now());
        fastRejectFilter.observeDecision(decision);
        decisionLogPort.append(decision);
        performanceMetrics.recordDecisionDurable(decision);
    }
}
