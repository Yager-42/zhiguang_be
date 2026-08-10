package com.tongji.promotion.bprime.realtime;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PromotionPublicUpdateCoalescerTest {

    @Test
    void keepsLatestBidPerCampaignAndPublishesCoveredDecisionRanges() {
        PromotionAuctionRealtimePublisher publisher = mock(PromotionAuctionRealtimePublisher.class);
        PromotionPerformanceMetrics metrics = mock(PromotionPerformanceMetrics.class);
        PromotionPublicUpdateCoalescer coalescer = new PromotionPublicUpdateCoalescer(
                publisher, mock(ThreadPoolTaskScheduler.class), metrics, new PromotionBPrimeProperties());
        coalescer.enqueueBid(decision("d-1", 1L, 0L, 201L, 120L));
        coalescer.enqueueBid(decision("d-2", 2L, 1L, 201L, 130L));
        coalescer.flushNow();
        coalescer.enqueueBid(decision("d-3", 3L, 2L, 202L, 140L));
        coalescer.flushNow();

        ArgumentCaptor<PromotionAuctionRealtimeEvent> events =
                ArgumentCaptor.forClass(PromotionAuctionRealtimeEvent.class);
        verify(publisher, times(2)).publishPublic(events.capture());
        PromotionAuctionRealtimeEvent first = events.getAllValues().get(0);
        PromotionAuctionRealtimeEvent second = events.getAllValues().get(1);
        assertThat(first.eventType()).isEqualTo(PromotionAuctionRealtimeEvent.RANKING_DELTA);
        assertThat(first.eventVersion()).isEqualTo(2L);
        assertThat(first.fromDecisionVersion()).isEqualTo(1L);
        assertThat(first.toDecisionVersion()).isEqualTo(2L);
        assertThat(first.ranking()).isEmpty();
        assertThat(first.bidDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.campaignId()).isEqualTo("201");
            assertThat(delta.bidAmount()).isEqualTo(130L);
        });
        assertThat(second.eventVersion()).isEqualTo(3L);
        assertThat(second.fromDecisionVersion()).isEqualTo(3L);
        assertThat(second.toDecisionVersion()).isEqualTo(3L);
        assertThat(second.bidDeltas()).extracting(PromotionBidDelta::campaignId).containsExactly("202");
        verify(metrics, times(2)).recordPublicUpdateBatch(1);
    }

    private PromotionAuctionDecision decision(
            String decisionId,
            long decisionVersion,
            long previousVersion,
            long campaignId,
            long bidAmount) {
        return new PromotionAuctionDecision(
                decisionId,
                "cmd-" + decisionVersion,
                "hash",
                301L,
                decisionVersion,
                previousVersion,
                campaignId,
                42L,
                1001L,
                "FEED_TOP_SLOT",
                "BID_ACCEPTED",
                true,
                null,
                bidAmount,
                List.of(),
                List.of(),
                Map.of(),
                Instant.parse("2026-06-20T10:05:00Z").plusMillis(decisionVersion));
    }
}
