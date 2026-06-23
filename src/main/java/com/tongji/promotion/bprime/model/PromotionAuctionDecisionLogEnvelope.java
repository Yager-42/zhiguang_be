package com.tongji.promotion.bprime.model;

import java.time.Instant;
import java.util.Objects;

public record PromotionAuctionDecisionLogEnvelope(int schemaVersion,
                                                  String eventType,
                                                  PromotionAuctionDecision decision,
                                                  String decisionHash,
                                                  Instant producedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String AUCTION_DECISION_EVENT_TYPE = "AUCTION_DECISION";

    public PromotionAuctionDecisionLogEnvelope {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + CURRENT_SCHEMA_VERSION);
        }
        if (!AUCTION_DECISION_EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + AUCTION_DECISION_EVENT_TYPE);
        }
        Objects.requireNonNull(decision, "decision");
        requireText(decisionHash, "decisionHash");
        Objects.requireNonNull(producedAt, "producedAt");
    }

    public static PromotionAuctionDecisionLogEnvelope auctionDecision(PromotionAuctionDecision decision,
                                                                      String decisionHash,
                                                                      Instant producedAt) {
        return new PromotionAuctionDecisionLogEnvelope(CURRENT_SCHEMA_VERSION, AUCTION_DECISION_EVENT_TYPE,
                decision, decisionHash, producedAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
