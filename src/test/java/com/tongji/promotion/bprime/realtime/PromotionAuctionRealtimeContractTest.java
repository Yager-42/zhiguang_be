package com.tongji.promotion.bprime.realtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionAuctionRealtimeContractTest {

    @Test
    void definesOnlyPromotionAuctionChannelsForThisChange() {
        assertThat(PromotionAuctionRealtimeChannels.ENDPOINT).isEqualTo("/ws/promotion-auction");
        assertThat(PromotionAuctionRealtimeChannels.NATIVE_ENDPOINT)
                .isEqualTo("/ws/promotion-auction-native");
        assertThat(PromotionAuctionRealtimeChannels.APPLICATION_PREFIX).isEqualTo("/app");
        assertThat(PromotionAuctionRealtimeChannels.BID_APPLICATION_DESTINATION)
                .isEqualTo("/promotion-auctions/bids");
        assertThat(PromotionAuctionRealtimeChannels.publicTopic(301L))
                .isEqualTo("/topic/promotion-auctions/301");
        assertThat(PromotionAuctionRealtimeChannels.PRIVATE_OUTCOME_QUEUE)
                .isEqualTo("/queue/promotion-auction-outcomes");
        assertThat(PromotionAuctionRealtimeChannels.PRIVATE_BID_ACK_QUEUE)
                .isEqualTo("/queue/promotion-auction-bid-acks");
    }

    @Test
    void publicEventCarriesVersionedBidDeltasWithoutFullRanking() {
        PromotionAuctionRealtimeEvent event = new PromotionAuctionRealtimeEvent(
                "decision-d-1:public",
                PromotionAuctionRealtimeEvent.RANKING_DELTA,
                301L,
                "d-1",
                2L,
                2L,
                "OPEN",
                List.of(),
                List.of(new PromotionBidDelta("201", "42", "1001", 120L)),
                Instant.parse("2026-06-20T10:05:00Z"));

        assertThat(event.decisionVersion()).isEqualTo(2L);
        assertThat(event.ranking()).isEmpty();
        assertThat(event.bidDeltas()).hasSize(1);
    }

    @Test
    void privateOutcomeCarriesBidResultFields() {
        PromotionAuctionOutcomeEvent event = new PromotionAuctionOutcomeEvent(
                "decision-d-1:outcome",
                PromotionAuctionOutcomeEvent.BID_REJECTED,
                301L,
                42L,
                "cmd-1",
                "d-1",
                3L,
                80L,
                "BELOW_RESERVE",
                Instant.parse("2026-06-20T10:05:00Z"));

        assertThat(event.commandId()).isEqualTo("cmd-1");
        assertThat(event.rejectionReason()).isEqualTo("BELOW_RESERVE");
    }
}
