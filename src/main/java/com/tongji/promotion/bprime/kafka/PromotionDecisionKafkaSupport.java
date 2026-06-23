package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Objects;

@Component
public class PromotionDecisionKafkaSupport {

    private static final Set<String> SUPPORTED_DECISION_TYPES = Set.of(
            "BID_ACCEPTED", "BID_REJECTED", "WINDOW_CLOSED");

    public PromotionAuctionDecision requireDecision(PromotionAuctionDecisionLogEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("promotion decision envelope is required");
        }
        if (envelope.schemaVersion() != PromotionAuctionDecisionLogEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema version mismatch");
        }
        if (!PromotionAuctionDecisionLogEnvelope.AUCTION_DECISION_EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("event type mismatch");
        }
        PromotionAuctionDecision decision = envelope.decision();
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (!SUPPORTED_DECISION_TYPES.contains(decision.type())) {
            throw new IllegalArgumentException("unsupported promotion decision type: " + decision.type());
        }
        if (!Objects.equals(PromotionDecisionHasher.hash(decision), envelope.decisionHash())) {
            throw new IllegalArgumentException("decision hash mismatch");
        }
        return decision;
    }

    public PromotionAuctionDecision requireDecision(String kafkaKey, PromotionAuctionDecisionLogEnvelope envelope) {
        PromotionAuctionDecision decision = requireDecision(envelope);
        if (!Objects.equals(kafkaKey, String.valueOf(decision.auctionWindowId()))) {
            throw new IllegalArgumentException("Kafka key must match auctionWindowId");
        }
        return decision;
    }
}
