package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;

public interface PromotionDecisionLogPort {
    void append(PromotionAuctionDecision decision);
}
