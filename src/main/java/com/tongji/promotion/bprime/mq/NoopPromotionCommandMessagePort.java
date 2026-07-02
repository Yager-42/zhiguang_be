package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "false", matchIfMissing = true)
public class NoopPromotionCommandMessagePort implements PromotionCommandMessagePort {
    @Override
    public void send(PromotionAuctionCommand command) {
    }
}
