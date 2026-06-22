package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class RocketMqPromotionCommandMessagePort implements PromotionCommandMessagePort {

    private final RocketMQTemplate rocketMQTemplate;
    private final PromotionBPrimeProperties properties;

    public RocketMqPromotionCommandMessagePort(RocketMQTemplate rocketMQTemplate,
                                               PromotionBPrimeProperties properties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
    }

    @Override
    public void send(PromotionAuctionCommand command) {
        SendResult result = rocketMQTemplate.syncSendOrderly(
                properties.getCommandTopic(), command, String.valueOf(command.auctionWindowId()));
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Failed to send promotion command to RocketMQ: " + result);
        }
    }
}
