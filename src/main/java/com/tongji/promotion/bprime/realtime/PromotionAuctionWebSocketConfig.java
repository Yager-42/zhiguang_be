package com.tongji.promotion.bprime.realtime;

import com.tongji.auth.token.JwtService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocket
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionAuctionWebSocketConfig
        implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private final JwtService jwtService;
    private final PromotionBPrimeProperties properties;
    private final PromotionNativeBidWebSocketHandler nativeBidWebSocketHandler;

    public PromotionAuctionWebSocketConfig(
            JwtService jwtService,
            PromotionBPrimeProperties properties,
            PromotionNativeBidWebSocketHandler nativeBidWebSocketHandler) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.nativeBidWebSocketHandler = nativeBidWebSocketHandler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(PromotionAuctionRealtimeChannels.ENDPOINT)
                .setHandshakeHandler(new PromotionAuctionHandshakeHandler(jwtService))
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(nativeBidWebSocketHandler, PromotionAuctionRealtimeChannels.NATIVE_ENDPOINT)
                .setHandshakeHandler(new PromotionAuctionHandshakeHandler(jwtService))
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes(PromotionAuctionRealtimeChannels.APPLICATION_PREFIX);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        configureChannelExecutor(registration, properties.getWebSocketInboundThreadCount());
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        configureChannelExecutor(registration, properties.getWebSocketOutboundThreadCount());
    }

    private void configureChannelExecutor(ChannelRegistration registration, int threadCount) {
        registration.taskExecutor()
                .corePoolSize(threadCount)
                .maxPoolSize(threadCount)
                .queueCapacity(properties.getWebSocketChannelQueueCapacity());
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
