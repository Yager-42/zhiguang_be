package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative auction decision. Consumers ask for typed domain facts; payload remains only as the
 * wire-format boundary shared with Redis Lua and persisted Stream JSON.
 */
public record PromotionAuctionDecision(
        String decisionId,
        String commandId,
        String requestHash,
        long auctionWindowId,
        long decisionVersion,
        long previousVersion,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        @JsonProperty("type") @JsonAlias("decisionType") String type,
        boolean accepted,
        String rejectionReason,
        long bidAmount,
        List<PromotionRankingItem> ranking,
        List<PromotionWalletEffect> walletEffects,
        Map<String, Object> payload,
        Instant decidedAt
) {
    public PromotionAuctionDecision {
        ranking = ranking == null ? List.of() : List.copyOf(ranking);
        walletEffects = walletEffects == null ? List.of() : List.copyOf(walletEffects);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        decisionTypeOf(type);
    }

    public PromotionAuctionDecision(String decisionId,
                                    String commandId,
                                    String requestHash,
                                    long auctionWindowId,
                                    long campaignId,
                                    long bidderUserId,
                                    long postId,
                                    String resourceType,
                                    String decisionType,
                                    boolean accepted,
                                    String rejectionReason,
                                    long bidAmount,
                                    List<PromotionRankingItem> ranking,
                                    List<PromotionWalletEffect> walletEffects,
                                    Instant decidedAt) {
        this(decisionId, commandId, requestHash, auctionWindowId, 1L, 0L, campaignId, bidderUserId, postId,
                resourceType, decisionType, accepted, rejectionReason, bidAmount, ranking, walletEffects, Map.of(),
                decidedAt);
    }

    public String decisionType() {
        return type;
    }

    public PromotionDecisionType kind() {
        return decisionTypeOf(type);
    }

    public boolean affectsAdmissionState() {
        return switch (kind()) {
            case BID_ACCEPTED, AUCTION_SOLD, AUCTION_NO_BID -> true;
            default -> false;
        };
    }

    public boolean terminal() {
        return kind() == PromotionDecisionType.AUCTION_SOLD
                || kind() == PromotionDecisionType.AUCTION_NO_BID;
    }

    public BidFacts bidFacts() {
        requireKind(PromotionDecisionType.BID_ACCEPTED, PromotionDecisionType.BID_REJECTED);
        return new BidFacts(
                optionalLong("authorizedAmount"),
                optionalLong("requiredAmount"),
                optionalLong("currentPriceCents"),
                optionalLong("nextRequiredAmount"),
                optionalString("winnerCampaignId"),
                optionalInstant("submittedAt"));
    }

    public AdmissionFacts admissionFacts() {
        return switch (kind()) {
            case BID_ACCEPTED -> new AdmissionFacts(
                    longOr("currentPriceCents", bidAmount),
                    stringOr("winnerCampaignId", String.valueOf(campaignId)),
                    "OPEN",
                    longOr("endAtEpochMs", Long.MAX_VALUE),
                    commandId);
            case AUCTION_SOLD -> {
                TerminalFacts terminal = terminalFacts();
                yield new AdmissionFacts(
                        terminal.winningAmount().orElse(bidAmount),
                        terminal.winnerCampaignId().map(String::valueOf).orElse("0"),
                        "SOLD",
                        terminal.actualEndAtEpochMs(),
                        null);
            }
            case AUCTION_NO_BID -> new AdmissionFacts(
                    longOr("currentPriceCents", bidAmount),
                    "0",
                    "NO_BID",
                    terminalFacts().actualEndAtEpochMs(),
                    null);
            default -> throw unsupportedFacts("admission");
        };
    }


    public TerminalFacts terminalFacts() {
        requireKind(PromotionDecisionType.AUCTION_SOLD, PromotionDecisionType.AUCTION_NO_BID);
        if (kind() == PromotionDecisionType.AUCTION_NO_BID) {
            return new TerminalFacts(Optional.empty(), Optional.empty(), longOr("actualEndAtEpochMs", 0L),
                    stringOr("finalWindowStatus", "SETTLED"));
        }
        return new TerminalFacts(Optional.of(requiredLong("winnerCampaignId")),
                Optional.of(requiredLong("winningAmount")), longOr("actualEndAtEpochMs", 0L),
                stringOr("finalWindowStatus", "SETTLED"));
    }

    public Optional<Instant> submittedAt() {
        return optionalInstant("submittedAt");
    }
    public boolean hasSubmittedAtPayload() {
        return payload.containsKey("submittedAt");
    }

    public PromotionAuctionDecision withRanking(List<PromotionRankingItem> finalRanking) {
        return new PromotionAuctionDecision(decisionId, commandId, requestHash, auctionWindowId,
                decisionVersion, previousVersion, campaignId, bidderUserId, postId, resourceType, type,
                accepted, rejectionReason, bidAmount, finalRanking, walletEffects, payload, decidedAt);
    }

    private static PromotionDecisionType decisionTypeOf(String value) {
        try {
            return PromotionDecisionType.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unsupported promotion decision type: " + value, exception);
        }
    }

    private void requireKind(PromotionDecisionType... supported) {
        PromotionDecisionType actual = kind();
        for (PromotionDecisionType candidate : supported) {
            if (actual == candidate) {
                return;
            }
        }
        throw unsupportedFacts("requested");
    }

    private IllegalStateException unsupportedFacts(String facts) {
        return new IllegalStateException("promotion " + facts + " facts are unavailable for " + type);
    }

    private long requiredLong(String field) {
        return optionalLong(field).orElseThrow(() -> new IllegalStateException(
                "promotion " + type + " decision is missing payload field: " + field));
    }

    private long longOr(String field, long fallback) {
        return optionalLong(field).orElse(fallback);
    }

    private String stringOr(String field, String fallback) {
        return optionalString(field).orElse(fallback);
    }

    private Optional<Long> optionalLong(String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Optional.of(Long.parseLong(text));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("promotion " + type + " payload field is not numeric: " + field,
                        exception);
            }
        }
        return Optional.empty();
    }

    private Optional<String> optionalString(String field) {
        Object value = payload.get(field);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private Optional<Instant> optionalInstant(String field) {
        Object value = payload.get(field);
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Optional.of(Instant.parse(text));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public record BidFacts(Optional<Long> authorizedAmount,
                           Optional<Long> requiredAmount,
                           Optional<Long> currentPriceCents,
                           Optional<Long> nextRequiredAmount,
                           Optional<String> winnerCampaignId,
                           Optional<Instant> submittedAt) {
    }

    public record AdmissionFacts(long currentPriceCents,
                                 String winnerCampaignId,
                                 String status,
                                 long actualEndAtEpochMs,
                                 String winnerCommandId) {
    }


    public record TerminalFacts(Optional<Long> winnerCampaignId,
                                Optional<Long> winningAmount,
                                long actualEndAtEpochMs,
                                String finalWindowStatus) {
    }
}
