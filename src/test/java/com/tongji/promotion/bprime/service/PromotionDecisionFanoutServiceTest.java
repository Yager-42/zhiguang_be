package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.bprime.realtime.PromotionAuctionOutcomeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimeEvent;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimePublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PromotionDecisionFanoutServiceTest {

    private final PromotionAuctionRealtimePublisher publisher = mock(PromotionAuctionRealtimePublisher.class);
    private final PromotionDecisionFanoutService service = new PromotionDecisionFanoutService(publisher);

    @Test
    void acceptedDecisionPublishesPublicRankingAndPrivateOutcome() {
        service.publishDecision(acceptedDecision());

        verify(publisher).publishPublic(argThat(event ->
                PromotionAuctionRealtimeEvent.RANKING_UPDATED.equals(event.eventType())
                        && event.auctionWindowId() == 301L
                        && event.decisionVersion() == 2L));
        verify(publisher).publishOutcome(argThat(event ->
                PromotionAuctionOutcomeEvent.BID_CONFIRMED.equals(event.eventType())
                        && event.bidderUserId() == 42L
                        && event.commandId().equals("cmd-1")));
    }

    @Test
    void duplicateDecisionPublishesOnce() {
        PromotionAuctionDecision decision = acceptedDecision();

        service.publishDecision(decision);
        service.publishDecision(decision);

        verify(publisher, times(1)).publishPublic(org.mockito.ArgumentMatchers.any());
        verify(publisher, times(1)).publishOutcome(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryAfterPublisherFailureMustPublishSameDecisionAgain() {
        PromotionAuctionDecision decision = acceptedDecision();
        doThrow(new RuntimeException("ws down"))
                .doNothing()
                .when(publisher).publishPublic(any(PromotionAuctionRealtimeEvent.class));

        assertThatThrownBy(() -> service.publishDecision(decision))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ws down");

        service.publishDecision(decision);

        verify(publisher, times(2)).publishPublic(any(PromotionAuctionRealtimeEvent.class));
    }

    @Test
    void rejectsSameVersionWithDifferentDecisionIdInsteadOfTreatingItAsDuplicate() {
        service.publishDecision(acceptedDecision());

        assertThatThrownBy(() -> service.publishDecision(new PromotionAuctionDecision("d-fork", "cmd-fork", "hash",
                301L, 2L, 1L, 202L, 43L, 1002L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 130L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:06:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion fanout decision version gap");
    }

    @Test
    void windowClosedPublishesTerminalPublicEvent() {
        service.publishDecision(new PromotionAuctionDecision("d-close", "close-cmd", "hash", 301L, 3L, 2L,
                0L, 0L, 0L, "FEED_TOP_SLOT", "WINDOW_CLOSED", false, null, 0L,
                List.of(new PromotionRankingItem("201", "42", "1001", 120L, 1)), List.of(),
                Map.of("finalWindowStatus", "SETTLED"), Instant.parse("2026-06-20T11:00:00Z")));

        verify(publisher).publishPublic(argThat(event ->
                PromotionAuctionRealtimeEvent.WINDOW_CLOSED.equals(event.eventType())
                        && "SETTLED".equals(event.windowStatus())));
    }

    @Test
    void rejectedDecisionPublishesPrivateRejectedOutcomeOnly() {
        service.publishDecision(new PromotionAuctionDecision("d-reject", "cmd-reject", "hash",
                301L, 2L, 1L, 201L, 42L, 1001L, "FEED_TOP_SLOT", "BID_REJECTED", false,
                "BELOW_RESERVE", 120L, List.of(), List.of(), Map.of(),
                Instant.parse("2026-06-20T10:05:00Z")));

        verify(publisher, never()).publishPublic(org.mockito.ArgumentMatchers.any());
        verify(publisher).publishOutcome(argThat(event ->
                PromotionAuctionOutcomeEvent.BID_REJECTED.equals(event.eventType())
                        && event.bidderUserId() == 42L
                        && "BELOW_RESERVE".equals(event.rejectionReason())));
    }

    @Test
    void rejectsSkippedVersionAfterWindowHasVisibleState() {
        service.publishDecision(acceptedDecision());

        assertThatThrownBy(() -> service.publishDecision(new PromotionAuctionDecision("d-gap", "cmd-gap", "hash",
                301L, 4L, 3L, 202L, 43L, 1002L, "FEED_TOP_SLOT", "BID_ACCEPTED", true,
                null, 130L, List.of(), List.of(), Map.of(), Instant.parse("2026-06-20T10:06:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion fanout decision version gap");
    }

    private PromotionAuctionDecision acceptedDecision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 2L, 1L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L,
                List.of(new PromotionRankingItem("201", "42", "1001", 120L, 1)), List.of(), Map.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
