package com.tongji.promotion.bprime.realtime;

import com.tongji.auth.token.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionAuctionWebSocketConfigTest {

    @Test
    void resolvesBearerTokenToUserPrincipal() {
        JwtService jwtService = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        when(jwtService.decode("token-1")).thenReturn(jwt);
        when(jwtService.extractUserId(jwt)).thenReturn(42L);
        ServerHttpRequest request = request("ws://localhost/ws/promotion-auction", "Bearer token-1");

        Principal principal = new TestHandshakeHandler(jwtService).determine(request);

        assertThat(principal.getName()).isEqualTo("42");
    }

    @Test
    void resolvesAccessTokenQueryToUserPrincipal() {
        JwtService jwtService = mock(JwtService.class);
        Jwt jwt = mock(Jwt.class);
        when(jwtService.decode("token-2")).thenReturn(jwt);
        when(jwtService.extractUserId(jwt)).thenReturn(43L);
        ServerHttpRequest request = request("ws://localhost/ws/promotion-auction?access_token=token-2", null);

        Principal principal = new TestHandshakeHandler(jwtService).determine(request);

        assertThat(principal.getName()).isEqualTo("43");
    }

    @Test
    void missingTokenGetsGuestPrincipal() {
        Principal principal = new TestHandshakeHandler(mock(JwtService.class))
                .determine(request("ws://localhost/ws/promotion-auction", null));

        assertThat(principal.getName()).isEqualTo("guest");
    }

    private ServerHttpRequest request(String uri, String authorization) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.add(HttpHeaders.AUTHORIZATION, authorization);
        }
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    private static class TestHandshakeHandler
            extends PromotionAuctionWebSocketConfig.PromotionAuctionHandshakeHandler {

        TestHandshakeHandler(JwtService jwtService) {
            super(jwtService);
        }

        Principal determine(ServerHttpRequest request) {
            return determineUser(request, null, new HashMap<>());
        }
    }
}
