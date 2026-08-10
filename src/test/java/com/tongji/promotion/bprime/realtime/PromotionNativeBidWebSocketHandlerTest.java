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
                "idem-1", "cmd-1", "301", "ACCEPTED", true, null);
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

}
