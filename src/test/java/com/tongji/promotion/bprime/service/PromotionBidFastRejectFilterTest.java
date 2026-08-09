package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionBidFastRejectFilterTest {

    private static final Instant NOW = Instant.parse("2026-06-20T10:05:00Z");

    private PromotionBidFastRejectFilter filter;

    @BeforeEach
    void setUp() {
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setFastRejectEnabled(true);
        filter = new PromotionBidFastRejectFilter(properties);
    }

    @Test
    void cacheMissAndOwnerMismatchAlwaysPassToAuthoritativePath() {
        assertThat(filter.findCandidate(42L, 201L, 80L, NOW)).isEmpty();

        filter.observeRoute(route(301L));

        assertThat(filter.findCandidate(43L, 201L, 80L, NOW)).isEmpty();
    }

    @Test
    void belowReserveAndExpiryBoundaryAlwaysDeferToAuthoritativePath() {
        filter.observeRoute(route(301L));
        filter.observeDecision(acceptedDecision(301L, 150L));

        assertThat(filter.findCandidate(42L, 201L, 99L, NOW)).isEmpty();
        assertThat(filter.findCandidate(42L, 201L, 150L,
                Instant.parse("2026-06-20T10:59:59Z"))).isEmpty();
    }

    @Test
    void acceptedBidAdvancesMonotonicWatermarkAndNeverRegresses() {
        filter.observeRoute(route(301L));
        filter.observeDecision(acceptedDecision(301L, 150L));
        filter.observeDecision(acceptedDecision(301L, 120L));

        assertThat(filter.findCandidate(42L, 201L, 150L, NOW)).isPresent();
        assertThat(filter.findCandidate(42L, 201L, 151L, NOW)).isEmpty();
    }

    @Test
    void aNewWindowResetsWatermarkAndIgnoresLateOldWindowDecision() {
        filter.observeRoute(route(301L));
        filter.observeDecision(acceptedDecision(301L, 150L));
        filter.observeRoute(route(302L));
        filter.observeDecision(acceptedDecision(301L, 200L));

        assertThat(filter.findCandidate(42L, 201L, 100L, NOW)).isEmpty();
    }

    @Test
    void concurrentAcceptedEventsKeepTheMaximumWatermark() throws InterruptedException {
        filter.observeRoute(route(301L));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (long amount = 101L; amount <= 500L; amount++) {
                long bidAmount = amount;
                executor.submit(() -> filter.observeDecision(acceptedDecision(301L, bidAmount)));
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(filter.findCandidate(42L, 201L, 500L, NOW)).isPresent();
        assertThat(filter.findCandidate(42L, 201L, 501L, NOW)).isEmpty();
    }

    private PromotionBidRoute route(long windowId) {
        return new PromotionBidRoute(201L, 42L, 1001L, windowId, "FEED_TOP_SLOT", 100L, 500L,
                "OPEN", Instant.parse("2026-06-20T11:00:00Z"));
    }

    private PromotionAuctionDecision acceptedDecision(long windowId, long bidAmount) {
        return new PromotionAuctionDecision("d-" + bidAmount, "cmd-" + bidAmount, "hash", windowId,
                201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, bidAmount,
                List.of(), List.of(), NOW);
    }
}
