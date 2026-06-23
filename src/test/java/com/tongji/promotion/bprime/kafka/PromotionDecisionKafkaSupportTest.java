package com.tongji.promotion.bprime.kafka;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import com.tongji.promotion.bprime.model.PromotionDecisionHasher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionDecisionKafkaSupportTest {

    private final PromotionDecisionKafkaSupport support = new PromotionDecisionKafkaSupport();

    @Test
    void acceptsValidEnvelope() {
        PromotionAuctionDecision decision = decision();
        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                decision, PromotionDecisionHasher.hash(decision), Instant.parse("2026-06-20T10:05:01Z"));

        assertThat(support.requireDecision(envelope)).isEqualTo(decision);
    }

    @Test
    void rejectsBadHash() {
        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                decision(), "bad-hash", Instant.parse("2026-06-20T10:05:01Z"));

        assertThatThrownBy(() -> support.requireDecision(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision hash mismatch");
    }

    @Test
    void rejectsInvalidSchemaVersionPayload() {
        String payload = """
                {"schemaVersion":2,"eventType":"AUCTION_DECISION","decision":{
                "decisionId":"d-1","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                "decisionVersion":2,"previousVersion":1,"campaignId":201,"bidderUserId":42,"postId":1001,
                "resourceType":"FEED_TOP_SLOT","type":"BID_ACCEPTED","accepted":true,"bidAmount":120,
                "ranking":[],"walletEffects":[],"payload":{},"decidedAt":"2026-06-20T10:05:00Z"},
                "decisionHash":"hash","producedAt":"2026-06-20T10:05:01Z"}
                """;

        assertThatThrownBy(() -> new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readValue(payload, PromotionAuctionDecisionLogEnvelope.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("schemaVersion must be 1");
    }

    @Test
    void rejectsInvalidEventTypePayload() {
        String payload = """
                {"schemaVersion":1,"eventType":"BID_ACCEPTED","decision":{
                "decisionId":"d-1","commandId":"cmd-1","requestHash":"hash","auctionWindowId":301,
                "decisionVersion":2,"previousVersion":1,"campaignId":201,"bidderUserId":42,"postId":1001,
                "resourceType":"FEED_TOP_SLOT","type":"BID_ACCEPTED","accepted":true,"bidAmount":120,
                "ranking":[],"walletEffects":[],"payload":{},"decidedAt":"2026-06-20T10:05:00Z"},
                "decisionHash":"hash","producedAt":"2026-06-20T10:05:01Z"}
                """;

        assertThatThrownBy(() -> new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readValue(payload, PromotionAuctionDecisionLogEnvelope.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("eventType must be AUCTION_DECISION");
    }

    @Test
    void rejectsMissingDecisionPayload() {
        assertThatThrownBy(() -> new PromotionAuctionDecisionLogEnvelope(
                PromotionAuctionDecisionLogEnvelope.CURRENT_SCHEMA_VERSION,
                PromotionAuctionDecisionLogEnvelope.AUCTION_DECISION_EVENT_TYPE,
                null,
                "hash",
                Instant.parse("2026-06-20T10:05:01Z")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decision");
    }

    @Test
    void rejectsUnknownDecisionType() {
        PromotionAuctionDecision decision = new PromotionAuctionDecision("d-unknown", "cmd-1", "hash",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_CANCELLED", false,
                "UNSUPPORTED", 120L, List.of(), List.of(), java.util.Map.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                decision, PromotionDecisionHasher.hash(decision), Instant.parse("2026-06-20T10:05:01Z"));

        assertThatThrownBy(() -> support.requireDecision(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported promotion decision type");
    }

    @Test
    void rejectsWindowClosedEnvelopeWhenFinalSettlementPayloadIsTampered() {
        PromotionAuctionDecision original = new PromotionAuctionDecision("d-close", "cmd-close", "hash",
                301L, 3L, 2L, 0L, 0L, 0L, "FEED_TOP_SLOT", "WINDOW_CLOSED", false,
                null, 0L, List.of(), List.of(), Map.of(
                "winners", List.of(Map.of(
                        "campaignId", 201L,
                        "bidderUserId", 42L,
                        "postId", 1001L,
                        "slotIndex", 0,
                        "clearingPrice", 100L)),
                "allocationStartAt", "2026-06-20T11:00:00Z",
                "allocationEndAt", "2026-06-20T12:00:00Z",
                "finalWindowStatus", "SETTLED"),
                Instant.parse("2026-06-20T11:00:00Z"));
        PromotionAuctionDecision tampered = new PromotionAuctionDecision("d-close", "cmd-close", "hash",
                301L, 3L, 2L, 0L, 0L, 0L, "FEED_TOP_SLOT", "WINDOW_CLOSED", false,
                null, 0L, List.of(), List.of(), Map.of(
                "winners", List.of(Map.of(
                        "campaignId", 999L,
                        "bidderUserId", 42L,
                        "postId", 1001L,
                        "slotIndex", 0,
                        "clearingPrice", 1L)),
                "allocationStartAt", "2026-06-20T11:00:00Z",
                "allocationEndAt", "2026-06-20T12:00:00Z",
                "finalWindowStatus", "SETTLED"),
                Instant.parse("2026-06-20T11:00:00Z"));
        PromotionAuctionDecisionLogEnvelope envelope = PromotionAuctionDecisionLogEnvelope.auctionDecision(
                tampered, PromotionDecisionHasher.hash(original), Instant.parse("2026-06-20T11:00:01Z"));

        assertThatThrownBy(() -> support.requireDecision(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision hash mismatch");
    }

    private PromotionAuctionDecision decision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 2L, 1L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(), List.of(), java.util.Map.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
