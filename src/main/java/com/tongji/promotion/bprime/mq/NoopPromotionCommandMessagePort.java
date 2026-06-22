package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(PromotionCommandMessagePort.class)
public class NoopPromotionCommandMessagePort implements PromotionCommandMessagePort {
    @Override
    public void send(PromotionAuctionCommand command) {
    }
}
