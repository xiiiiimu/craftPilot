package com.craftpilot.events;

import com.craftpilot.tenant.TenantAccess;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final GroupRealtimeHub hub;
    private final TenantAccess tenant;
    private final ObjectMapper mapper;

    public WebSocketConfiguration(GroupRealtimeHub hub, TenantAccess tenant, ObjectMapper mapper) {
        this.hub = hub;
        this.tenant = tenant;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler(), "/api/v1/ws")
                .addInterceptors(authInterceptor())
                .setAllowedOriginPatterns("*");
    }

    private HandshakeInterceptor authInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                var principal = request.getPrincipal();
                if (principal == null || principal.getName() == null) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attributes.put("actorId", UUID.fromString(principal.getName()));
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
            }
        };
    }

    private WebSocketHandler handler() {
        return new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                // wait for subscribe message
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                JsonNode node = mapper.readTree(message.getPayload());
                if (!"subscribe".equals(node.path("action").asText())) {
                    session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unsupported action"));
                    return;
                }
                UUID merchantId = UUID.fromString(node.path("merchantId").asText());
                UUID groupId = UUID.fromString(node.path("groupId").asText());
                UUID actor = (UUID) session.getAttributes().get("actorId");
                if (!tenant.groupRole(actor, merchantId, groupId).isPresent()) {
                    session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Forbidden"));
                    return;
                }
                hub.subscribe(session, merchantId, groupId);
                session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                        "type", "SUBSCRIBED",
                        "merchantId", merchantId,
                        "groupId", groupId))));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                hub.unsubscribe(session);
            }
        };
    }
}
