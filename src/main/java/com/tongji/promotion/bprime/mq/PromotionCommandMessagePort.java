package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.model.PromotionAuctionCommand;

public interface PromotionCommandMessagePort {
    void send(PromotionAuctionCommand command);
}
