package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionDecisionProjectionKafkaListener {

    private final ObjectMapper objectMapper;
    private final PromotionDecisionProjectionService projectionService;

    public PromotionDecisionProjectionKafkaListener(ObjectMapper objectMapper,
                                                    PromotionDecisionProjectionService projectionService) {
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
    }

    @KafkaListener(topics = "${promotion.bprime.decision-topic:zhiguang.promotion.auction.decisions.v2}",
            groupId = "${promotion.bprime.projection-consumer-group:zhiguang-promotion-projection-consumer}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        projectionService.project(objectMapper.readValue(record.value(), PromotionAuctionDecision.class),
                record.topic(), record.partition(), record.offset());
        ack.acknowledge();
    }
}
