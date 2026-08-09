package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionDecisionProjectionKafkaListener {

    private final ObjectMapper objectMapper;
    private final PromotionDecisionProjectionService projectionService;
    private final PromotionDecisionKafkaSupport support;
    private final PromotionPerformanceMetrics performanceMetrics;

    public PromotionDecisionProjectionKafkaListener(ObjectMapper objectMapper,
                                                    PromotionDecisionProjectionService projectionService,
                                                    PromotionPerformanceMetrics performanceMetrics) {
        this(objectMapper, projectionService, new PromotionDecisionKafkaSupport(), performanceMetrics);
    }

    @Autowired
    public PromotionDecisionProjectionKafkaListener(ObjectMapper objectMapper,
                                                    PromotionDecisionProjectionService projectionService,
                                                    PromotionDecisionKafkaSupport support,
                                                    PromotionPerformanceMetrics performanceMetrics) {
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
        this.support = support;
        this.performanceMetrics = performanceMetrics;
    }

    @KafkaListener(topics = "${promotion.bprime.decision-topic:zhiguang.promotion.auction.decisions.v2}",
            groupId = "${promotion.bprime.projection-consumer-group:zhiguang-promotion-projection-consumer}",
            containerFactory = "promotionDecisionBatchKafkaListenerContainerFactory")
    public void onMessage(List<ConsumerRecord<String, String>> records, Acknowledgment ack) throws Exception {
        List<PromotionDecisionProjectionItem> items = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            items.add(new PromotionDecisionProjectionItem(
                    support.requireDecision(record.key(), objectMapper.readValue(
                            record.value(), PromotionAuctionDecisionLogEnvelope.class)),
                    record.topic(), record.partition(), record.offset()));
        }
        List<com.tongji.promotion.bprime.model.PromotionAuctionDecision> projectedDecisions =
                projectionService.projectBatch(items);
        projectedDecisions.forEach(performanceMetrics::recordProjectionComplete);
        ack.acknowledge();
    }
}
