package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;

import java.util.concurrent.CompletionStage;

public interface PromotionDecisionLogPort {
    void append(PromotionAuctionDecision decision);

    CompletionStage<Void> appendAsync(PromotionAuctionDecision decision);
}
