package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.service.PromotionDecisionFanoutService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"promotion.bprime.enabled", "promotion.bprime.fanout-consumer-enabled"},
        havingValue = "true")
public class PromotionDecisionFanoutKafkaListener {

    private final ObjectMapper objectMapper;
    private final PromotionDecisionKafkaSupport support;
    private final PromotionDecisionFanoutService fanoutService;
    private final PromotionPerformanceMetrics performanceMetrics;

    public PromotionDecisionFanoutKafkaListener(ObjectMapper objectMapper,
                                                PromotionDecisionKafkaSupport support,
                                                PromotionDecisionFanoutService fanoutService,
                                                PromotionPerformanceMetrics performanceMetrics) {
        this.objectMapper = objectMapper;
        this.support = support;
        this.fanoutService = fanoutService;
        this.performanceMetrics = performanceMetrics;
    }

    @KafkaListener(topics = "${promotion.bprime.decision-topic:zhiguang.promotion.auction.decisions.v2}",
            groupId = "${promotion.bprime.fanout-consumer-group:zhiguang-promotion-fanout-consumer}",
            containerFactory = "promotionDecisionFanoutKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        PromotionAuctionDecision decision = support.requireDecision(record.key(),
                objectMapper.readValue(record.value(), PromotionAuctionDecisionLogEnvelope.class));
        if (fanoutService.publishDecision(decision)) {
            performanceMetrics.recordRealtimeComplete(decision);
        }
        ack.acknowledge();
    }
}
