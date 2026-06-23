package com.tongji.promotion.bprime.realtime;

import com.tongji.auth.token.JwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;

    public PromotionAuctionWebSocketConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(PromotionAuctionRealtimeChannels.ENDPOINT)
                .setHandshakeHandler(new PromotionAuctionHandshakeHandler(jwtService))
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    static class PromotionAuctionHandshakeHandler extends DefaultHandshakeHandler {

        private final JwtService jwtService;

        PromotionAuctionHandshakeHandler(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            String token = bearerToken(request);
            if (token == null) {
                token = queryToken(request);
            }
            if (token == null || token.isBlank()) {
                return new PromotionAuctionWebSocketPrincipal("guest");
            }
            try {
                return new PromotionAuctionWebSocketPrincipal(String.valueOf(
                        jwtService.extractUserId(jwtService.decode(token))));
            } catch (RuntimeException ignored) {
                return new PromotionAuctionWebSocketPrincipal("guest");
            }
        }

        private String bearerToken(ServerHttpRequest request) {
            String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer ")) {
                return null;
            }
            return header.substring("Bearer ".length());
        }

        private String queryToken(ServerHttpRequest request) {
            String query = request.getURI().getRawQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String part : query.split("&")) {
                int split = part.indexOf('=');
                if (split > 0 && "access_token".equals(part.substring(0, split))) {
                    return part.substring(split + 1);
                }
            }
            return null;
        }
    }
}
