package com.tongji.promotion.bprime.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.realtime.PromotionPublicUpdateCoalescer;
import com.tongji.promotion.bprime.redis.PromotionRedisSnapshotAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只从权威 Stream 事件生成公共排名与关窗广播。
 */
@Service
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionDecisionFanoutService {

    private static final long MAX_TRACKED_WINDOWS = 200_000L;

    private final PromotionPublicUpdateCoalescer publicUpdateCoalescer;
    private final PromotionRedisSnapshotAdapter snapshotAdapter;
    private final Cache<Long, VisibleDecision> lastVisibleDecisions = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_WINDOWS)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public PromotionDecisionFanoutService(PromotionPublicUpdateCoalescer publicUpdateCoalescer,
                                          PromotionRedisSnapshotAdapter snapshotAdapter) {
        this.publicUpdateCoalescer = publicUpdateCoalescer;
        this.snapshotAdapter = snapshotAdapter;
    }

    public boolean publishDecision(PromotionAuctionDecision decision) {
        VisibleDecision previous = lastVisibleDecisions.getIfPresent(decision.auctionWindowId());
        if (!requireVisibleVersionOrder(decision)) {
            return false;
        }
        try {
            switch (decision.kind()) {
                case BID_ACCEPTED -> publicUpdateCoalescer.enqueueBid(decision);
                case AUCTION_SOLD, AUCTION_NO_BID ->
                        publicUpdateCoalescer.publishWindowClosed(withFinalRanking(decision));
                default -> throw new IllegalArgumentException(
                        "unsupported promotion Stream event: " + decision.type());
            }
        } catch (RuntimeException exception) {
            rollbackVisibleDecision(decision, previous);
            throw exception;
        }
        return true;
    }

    private PromotionAuctionDecision withFinalRanking(PromotionAuctionDecision decision) {
        return decision.withRanking(snapshotAdapter.snapshot(decision.auctionWindowId()).ranking());
    }

    private boolean requireVisibleVersionOrder(PromotionAuctionDecision decision) {
        AtomicBoolean shouldPublish = new AtomicBoolean(true);
        lastVisibleDecisions.asMap().compute(decision.auctionWindowId(), (windowId, last) -> {
            if (last == null) {
                return new VisibleDecision(decision.decisionVersion(), decision.decisionId());
            }
            if (decision.decisionVersion() < last.decisionVersion()) {
                shouldPublish.set(false);
                return last;
            }
            if (decision.decisionVersion() == last.decisionVersion()) {
                if (!last.decisionId().equals(decision.decisionId())) {
                    throw new IllegalStateException("promotion fanout conflicting decision version: "
                            + decision.decisionVersion());
                }
                shouldPublish.set(false);
                return last;
            }
            if (decision.previousVersion() != last.decisionVersion()
                    || decision.decisionVersion() != last.decisionVersion() + 1) {
                throw new IllegalStateException("promotion fanout decision version gap: auctionWindowId="
                        + decision.auctionWindowId() + ", last=" + last.decisionVersion()
                        + ", previous=" + decision.previousVersion()
                        + ", current=" + decision.decisionVersion());
            }
            return new VisibleDecision(decision.decisionVersion(), decision.decisionId());
        });
        return shouldPublish.get();
    }

    private void rollbackVisibleDecision(PromotionAuctionDecision decision, VisibleDecision previous) {
        lastVisibleDecisions.asMap().computeIfPresent(decision.auctionWindowId(), (windowId, current) -> {
            if (current.decisionVersion() == decision.decisionVersion()
                    && current.decisionId().equals(decision.decisionId())) {
                return previous;
            }
            return current;
        });
    }

    private record VisibleDecision(long decisionVersion, String decisionId) {
    }
}
