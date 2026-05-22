package com.chatroom.config;

import com.chatroom.websocket.AuthHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Raw WebSocket endpoint (no SockJS) — used by test scripts and non-browser clients
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authHandshakeInterceptor)
                .setHandshakeHandler(handshakeHandler());

        // SockJS endpoint — used by browser clients
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authHandshakeInterceptor)
                .setHandshakeHandler(handshakeHandler())
                .withSockJS();
    }

    private HandshakeHandler handshakeHandler() {
        return new DefaultHandshakeHandler() {
            @Override
            protected Principal determineUser(
                    org.springframework.http.server.ServerHttpRequest request,
                    org.springframework.web.socket.WebSocketHandler wsHandler,
                    Map<String, Object> attributes) {
                Long userId = (Long) attributes.get("userId");
                if (userId != null) {
                    return userId::toString;
                }
                return super.determineUser(request, wsHandler, attributes);
            }
        };
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new UserSubscriptionInterceptor());
        // Scale thread pool for stress testing (many concurrent WS connections)
        registration.taskExecutor().corePoolSize(20);
        registration.taskExecutor().maxPoolSize(100);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(20);
        registration.taskExecutor().maxPoolSize(100);
    }
}
