package com.tongji.promotion.bprime.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionNativeBidWebSocketHandlerTest {

    private PromotionBidWebSocketProtocolService protocolService;
    private PromotionNativeBidWebSocketHandler handler;
    private WebSocketSession session;
    private PromotionPerformanceMetrics performanceMetrics;

    @BeforeEach
    void setUp() {
        protocolService = mock(PromotionBidWebSocketProtocolService.class);
        performanceMetrics = mock(PromotionPerformanceMetrics.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setWebSocketNativeSessionQueueCapacity(16);
        handler = new PromotionNativeBidWebSocketHandler(
                protocolService, new ObjectMapper().findAndRegisterModules(), Runnable::run,
                performanceMetrics, properties);
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.getPrincipal()).thenReturn((Principal) () -> "42");
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void nativeFrameUsesSharedProtocolAndSerializedWriterPump() throws Exception {
        PromotionWebSocketBidAck ack = new PromotionWebSocketBidAck(
                "idem-1", "cmd-1", "301", "PUBLISHED", false, null);
        when(protocolService.submit(any(), any())).thenReturn(CompletableFuture.completedFuture(ack));

        handler.handleMessage(session, new TextMessage(
                "{\"campaignId\":\"201\",\"bidAmount\":120,\"idempotencyKey\":\"idem-1\"}"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getPayload()).contains("\"commandId\":\"cmd-1\"");
    }

    @Test
    void malformedJsonReturnsBadRequestAck() throws Exception {
        when(protocolService.rejected(null, "BAD_REQUEST"))
                .thenReturn(PromotionWebSocketBidAck.rejected(null, "BAD_REQUEST"));

        handler.handleMessage(session, new TextMessage("{"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getPayload()).contains("\"rejectionReason\":\"BAD_REQUEST\"");
    }

    @Test
    void fullCriticalAckQueueClosesSlowConnectionWithTypedBackpressureStatus() throws Exception {
        List<Runnable> outboundTasks = new ArrayList<>();
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setWebSocketNativeSessionQueueCapacity(1);
        PromotionNativeBidWebSocketHandler boundedHandler = new PromotionNativeBidWebSocketHandler(
                protocolService, new ObjectMapper().findAndRegisterModules(), outboundTasks::add,
                performanceMetrics, properties);
        WebSocketSession slowSession = mock(WebSocketSession.class);
        when(slowSession.getId()).thenReturn("slow-session");
        when(slowSession.getPrincipal()).thenReturn((Principal) () -> "42");
        when(slowSession.isOpen()).thenReturn(true);
        PromotionWebSocketBidAck ack = new PromotionWebSocketBidAck(
                "idem-1", "cmd-1", "301", "REJECTED", true, "BID_NOT_HIGHER");
        when(protocolService.submit(any(), any())).thenReturn(CompletableFuture.completedFuture(ack));
        boundedHandler.afterConnectionEstablished(slowSession);

        boundedHandler.handleMessage(slowSession, new TextMessage(
                "{\"campaignId\":\"201\",\"bidAmount\":120,\"idempotencyKey\":\"idem-1\"}"));
        boundedHandler.handleMessage(slowSession, new TextMessage(
                "{\"campaignId\":\"201\",\"bidAmount\":120,\"idempotencyKey\":\"idem-2\"}"));
        outboundTasks.removeFirst().run();

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(slowSession).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4000);
        assertThat(outboundTasks).isEmpty();
        verify(protocolService, times(2)).submit(any(), any());
        verify(performanceMetrics).recordWebSocketBackpressureClose();
    }

    @Test
    void privateOutcomeIsWrittenBeforePendingPublicRoomUpdate() throws Exception {
        List<Runnable> outboundTasks = new ArrayList<>();
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        PromotionNativeBidWebSocketHandler prioritizedHandler = new PromotionNativeBidWebSocketHandler(
                protocolService, new ObjectMapper().findAndRegisterModules(), outboundTasks::add,
                performanceMetrics, properties);
        WebSocketSession prioritizedSession = mock(WebSocketSession.class);
        when(prioritizedSession.getId()).thenReturn("priority-session");
        when(prioritizedSession.getPrincipal()).thenReturn((Principal) () -> "42");
        when(prioritizedSession.isOpen()).thenReturn(true);
        prioritizedHandler.afterConnectionEstablished(prioritizedSession);
        prioritizedHandler.handleMessage(prioritizedSession,
                new TextMessage("{\"type\":\"SUBSCRIBE\",\"auctionWindowId\":301}"));
        outboundTasks.removeFirst().run();
        clearInvocations(prioritizedSession);

        PromotionAuctionRealtimeEvent publicEvent = new PromotionAuctionRealtimeEvent(
                "event-public", PromotionAuctionRealtimeEvent.RANKING_DELTA, 301L, "d-1",
                2L, 1L, "OPEN", List.of(), List.of(new PromotionBidDelta("201", "42", "1001", 120L)),
                java.time.Instant.parse("2026-06-20T10:05:00Z"));
        PromotionAuctionOutcomeEvent outcome = new PromotionAuctionOutcomeEvent(
                "event-outcome", PromotionAuctionOutcomeEvent.BID_CONFIRMED, 301L, 42L,
                "cmd-1", "d-1", 2L, 120L, null,
                java.time.Instant.parse("2026-06-20T10:05:00Z"));

        prioritizedHandler.publishPublic(publicEvent);
        prioritizedHandler.publishOutcome(outcome);
        outboundTasks.removeFirst().run();

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(prioritizedSession, times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().get(0).getPayload()).contains("BID_CONFIRMED");
        assertThat(messages.getAllValues().get(1).getPayload()).contains("RANKING_DELTA");
    }
}
