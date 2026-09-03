package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionHotSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.bprime.realtime.PromotionPublicUpdateCoalescer;
import com.tongji.promotion.bprime.redis.PromotionRedisSnapshotAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionDecisionFanoutServiceTest {

    private final PromotionPublicUpdateCoalescer coalescer = mock(PromotionPublicUpdateCoalescer.class);
    private final PromotionRedisSnapshotAdapter snapshotAdapter = mock(PromotionRedisSnapshotAdapter.class);
    private final PromotionDecisionFanoutService service =
            new PromotionDecisionFanoutService(coalescer, snapshotAdapter);

    @Test
    void acceptedStreamDecisionOnlyQueuesPublicDelta() {
        PromotionAuctionDecision decision = acceptedDecision();
        service.publishDecision(decision);
        service.publishDecision(decision);

        verify(coalescer, times(1)).enqueueBid(decision);
    }

    @Test
    void closeReadsFinalRankingAfterAuthoritativeStreamEvent() {
        PromotionRankingItem ranking = new PromotionRankingItem("201", "42", "1001", 120L, 1);
        when(snapshotAdapter.snapshot(301L)).thenReturn(new PromotionAuctionHotSnapshot(2L, List.of(ranking)));
        PromotionAuctionDecision close = decision("d-close", 2L, 1L, "AUCTION_SOLD");

        service.publishDecision(close);

        verify(coalescer).publishWindowClosed(argThat(value -> value.ranking().equals(List.of(ranking))));
    }


    @Test
    void noBidDecisionRoutesToWindowClosedEvent() {
        when(snapshotAdapter.snapshot(301L)).thenReturn(
                new PromotionAuctionHotSnapshot(2L, List.of()));
        PromotionAuctionDecision noBid = decision("d-nobid", 2L, 1L, "AUCTION_NO_BID");

        service.publishDecision(noBid);

        verify(coalescer).publishWindowClosed(argThat(value -> value.type().equals("AUCTION_NO_BID")));
    }

    @Test
    void versionGapStopsFanout() {
        service.publishDecision(acceptedDecision());

        assertThatThrownBy(() -> service.publishDecision(decision("d-gap", 3L, 2L, "BID_ACCEPTED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version gap");
    }

    private PromotionAuctionDecision acceptedDecision() {
        return decision("d-1", 1L, 0L, "BID_ACCEPTED");
    }

    private PromotionAuctionDecision decision(String id, long version, long previous, String type) {
        return new PromotionAuctionDecision(id, "cmd", "hash", 301L, version, previous,
                201L, 42L, 1001L, "FEED_TOP_SLOT", type, true, null, 120L,
                List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:05:00Z"));
    }
}
