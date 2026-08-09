package com.tongji.promotion.bprime.realtime;

import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionBidWebSocketControllerTest {

    private PromotionBidWebSocketProtocolService protocolService;
    private PromotionBidWebSocketController controller;

    @BeforeEach
    void setUp() {
        protocolService = mock(PromotionBidWebSocketProtocolService.class);
        controller = new PromotionBidWebSocketController(protocolService);
    }

    @Test
    void delegatesTransportRequestToSharedProtocolService() {
        PromotionWebSocketBidRequest request = new PromotionWebSocketBidRequest("201", 120L, "idem-1");
        Principal principal = principal("42");
        PromotionWebSocketBidAck ack = new PromotionWebSocketBidAck(
                "idem-1", "cmd-1", "301", "PUBLISHED", false, null);
        when(protocolService.submit(request, principal)).thenReturn(CompletableFuture.completedFuture(ack));

        PromotionWebSocketBidAck response = controller.submit(request, principal).join();

        assertThat(response).isEqualTo(ack);
        verify(protocolService).submit(request, principal);
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
