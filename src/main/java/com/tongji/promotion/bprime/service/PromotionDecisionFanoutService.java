package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.realtime.PromotionAuctionOutcomeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimePublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromotionDecisionFanoutService {

    private final PromotionAuctionRealtimePublisher publisher;
    private final Set<String> deliveredEventIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, VisibleDecision> lastVisibleDecisions = new ConcurrentHashMap<>();

    public PromotionDecisionFanoutService(PromotionAuctionRealtimePublisher publisher) {
        this.publisher = publisher;
    }

    public void publishDecision(PromotionAuctionDecision decision) {
        requireVisibleVersionOrder(decision);
        switch (decision.type()) {
            case "BID_ACCEPTED" -> {
                publishPublic(rankingEvent(decision));
                publishOutcome(outcomeEvent(decision, PromotionAuctionOutcomeEvent.BID_CONFIRMED));
            }
            case "BID_REJECTED" -> publishOutcome(outcomeEvent(decision, PromotionAuctionOutcomeEvent.BID_REJECTED));
            case "WINDOW_CLOSED" -> publishPublic(windowClosedEvent(decision));
            default -> throw new IllegalArgumentException("unsupported promotion decision type: " + decision.type());
        }
    }

    private void requireVisibleVersionOrder(PromotionAuctionDecision decision) {
        lastVisibleDecisions.compute(decision.auctionWindowId(), (auctionWindowId, last) -> {
            if (last == null) {
                return new VisibleDecision(decision.decisionVersion(), decision.decisionId());
            }
            long lastVersion = last.decisionVersion();
            if (decision.decisionVersion() <= lastVersion) {
                if (decision.decisionVersion() == lastVersion
                        && last.decisionId().equals(decision.decisionId())) {
                    return last;
                }
                throw new IllegalStateException("promotion fanout decision version gap: auctionWindowId="
                        + decision.auctionWindowId() + ", last=" + lastVersion
                        + ", previous=" + decision.previousVersion()
                        + ", current=" + decision.decisionVersion());
            }
            if (decision.previousVersion() != lastVersion || decision.decisionVersion() != lastVersion + 1) {
                throw new IllegalStateException("promotion fanout decision version gap: auctionWindowId="
                        + decision.auctionWindowId() + ", last=" + lastVersion
                        + ", previous=" + decision.previousVersion()
                        + ", current=" + decision.decisionVersion());
            }
            return new VisibleDecision(decision.decisionVersion(), decision.decisionId());
        });
    }

    private record VisibleDecision(long decisionVersion, String decisionId) {
    }

    private void publishPublic(PromotionAuctionRealtimeEvent event) {
        if (deliveredEventIds.add(event.eventId())) {
            try {
                publisher.publishPublic(event);
            } catch (RuntimeException e) {
                deliveredEventIds.remove(event.eventId());
                throw e;
            }
        }
    }

    private void publishOutcome(PromotionAuctionOutcomeEvent event) {
        if (deliveredEventIds.add(event.eventId())) {
            try {
                publisher.publishOutcome(event);
            } catch (RuntimeException e) {
                deliveredEventIds.remove(event.eventId());
                throw e;
            }
        }
    }

    private PromotionAuctionRealtimeEvent rankingEvent(PromotionAuctionDecision decision) {
        return new PromotionAuctionRealtimeEvent(
                "decision-" + decision.decisionId() + ":public",
                PromotionAuctionRealtimeEvent.RANKING_UPDATED,
                decision.auctionWindowId(),
                decision.decisionId(),
                decision.decisionVersion(),
                decision.decisionVersion(),
                "OPEN",
                decision.ranking(),
                decision.decidedAt());
    }

    private PromotionAuctionRealtimeEvent windowClosedEvent(PromotionAuctionDecision decision) {
        return new PromotionAuctionRealtimeEvent(
                "decision-" + decision.decisionId() + ":public",
                PromotionAuctionRealtimeEvent.WINDOW_CLOSED,
                decision.auctionWindowId(),
                decision.decisionId(),
                decision.decisionVersion(),
                decision.decisionVersion(),
                String.valueOf(decision.payload().getOrDefault("finalWindowStatus", "SETTLED")),
                decision.ranking(),
                decision.decidedAt());
    }

    private PromotionAuctionOutcomeEvent outcomeEvent(PromotionAuctionDecision decision, String eventType) {
        return new PromotionAuctionOutcomeEvent(
                "decision-" + decision.decisionId() + ":outcome",
                eventType,
                decision.auctionWindowId(),
                decision.bidderUserId(),
                decision.commandId(),
                decision.decisionId(),
                decision.decisionVersion(),
                decision.bidAmount(),
                decision.rejectionReason(),
                decision.decidedAt());
    }
}
