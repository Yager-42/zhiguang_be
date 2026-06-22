package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.service.PromotionCommandProcessingService;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${promotion.bprime.command-topic:zhiguang_promotion_auction_commands_v2}",
        consumerGroup = "${promotion.bprime.command-consumer-group:zhiguang-promotion-command-consumer}",
        consumeMode = ConsumeMode.ORDERLY)
public class PromotionCommandRocketMqListener implements RocketMQListener<PromotionAuctionCommand> {

    private final PromotionCommandProcessingService processingService;

    public PromotionCommandRocketMqListener(PromotionCommandProcessingService processingService) {
        this.processingService = processingService;
    }

    @Override
    public void onMessage(PromotionAuctionCommand command) {
        processingService.process(command);
    }
}
