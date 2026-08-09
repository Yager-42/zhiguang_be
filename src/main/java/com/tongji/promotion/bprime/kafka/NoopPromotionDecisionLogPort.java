package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "false", matchIfMissing = true)
public class NoopPromotionDecisionLogPort implements PromotionDecisionLogPort {

    @Override
    public void append(PromotionAuctionDecision decision) {
    }

    @Override
    public CompletionStage<Void> appendAsync(PromotionAuctionDecision decision) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void appendBatch(List<PromotionAuctionDecision> decisions) {
    }
}
