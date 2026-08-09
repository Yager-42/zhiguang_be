package com.tongji.promotion.bprime.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.realtime.PromotionAuctionOutcomeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimePublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PromotionDecisionFanoutService {

    private static final long MAX_TRACKED_EVENTS = 200_000L;

    private final PromotionAuctionRealtimePublisher publisher;
    private final Cache<String, Boolean> deliveredEventIds = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_EVENTS)
            .expireAfterWrite(Duration.ofHours(2))
            .build();
    private final Cache<Long, VisibleDecision> lastVisibleDecisions = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_EVENTS)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public PromotionDecisionFanoutService(PromotionAuctionRealtimePublisher publisher) {
        this.publisher = publisher;
    }

    public boolean publishDecision(PromotionAuctionDecision decision) {
        VisibleDecision previous = lastVisibleDecisions.getIfPresent(decision.auctionWindowId());
        if (!requireVisibleVersionOrder(decision)) {
            return false;
        }
        try {
            switch (decision.type()) {
                case "BID_ACCEPTED" -> {
                    publishPublic(rankingEvent(decision));
                    publishOutcome(outcomeEvent(decision, PromotionAuctionOutcomeEvent.BID_CONFIRMED));
                }
                case "BID_REJECTED" -> publishOutcome(outcomeEvent(decision, PromotionAuctionOutcomeEvent.BID_REJECTED));
                case "ESCROW_APPLIED" -> {
                    // 授权投影不对客户端广播，但仍推进窗口可见版本以保持后续事件连续。
                }
                case "WINDOW_CLOSED" -> publishPublic(windowClosedEvent(decision));
                default -> throw new IllegalArgumentException("unsupported promotion decision type: " + decision.type());
            }
        } catch (RuntimeException e) {
            rollbackVisibleDecision(decision, previous);
            throw e;
        }
        return true;
    }

    private void rollbackVisibleDecision(PromotionAuctionDecision decision, VisibleDecision previous) {
        lastVisibleDecisions.asMap().computeIfPresent(decision.auctionWindowId(), (auctionWindowId, current) -> {
            if (current.decisionVersion() == decision.decisionVersion()
                    && current.decisionId().equals(decision.decisionId())) {
                return previous;
            }
            return current;
        });
    }

    private boolean requireVisibleVersionOrder(PromotionAuctionDecision decision) {
        AtomicBoolean shouldPublish = new AtomicBoolean(true);
        lastVisibleDecisions.asMap().compute(decision.auctionWindowId(), (auctionWindowId, last) -> {
            if (last == null) {
                return new VisibleDecision(decision.decisionVersion(), decision.decisionId());
            }
            long lastVersion = last.decisionVersion();
            if (decision.decisionVersion() <= lastVersion) {
                if (decision.decisionVersion() == lastVersion
                        && last.decisionId().equals(decision.decisionId())) {
                    shouldPublish.set(false);
                    return last;
                }
                if (decision.decisionVersion() < lastVersion) {
                    shouldPublish.set(false);
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
        return shouldPublish.get();
    }

    private record VisibleDecision(long decisionVersion, String decisionId) {
    }

    private void publishPublic(PromotionAuctionRealtimeEvent event) {
        if (deliveredEventIds.asMap().putIfAbsent(event.eventId(), Boolean.TRUE) == null) {
            try {
                publisher.publishPublic(event);
            } catch (RuntimeException e) {
                deliveredEventIds.invalidate(event.eventId());
                throw e;
            }
        }
    }

    private void publishOutcome(PromotionAuctionOutcomeEvent event) {
        if (deliveredEventIds.asMap().putIfAbsent(event.eventId(), Boolean.TRUE) == null) {
            try {
                publisher.publishOutcome(event);
            } catch (RuntimeException e) {
                deliveredEventIds.invalidate(event.eventId());
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
