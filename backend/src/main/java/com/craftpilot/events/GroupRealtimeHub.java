package com.craftpilot.events;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Component
public class GroupRealtimeHub {
    private static final Logger LOG = LoggerFactory.getLogger(GroupRealtimeHub.class);

    private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public GroupRealtimeHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(WebSocketSession session, UUID merchantId, UUID groupId) {
        String key = key(merchantId, groupId);
        subscriptions.computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>()).add(session);
        session.getAttributes().put("subscriptionKey", key);
    }

    public void unsubscribe(WebSocketSession session) {
        Object key = session.getAttributes().get("subscriptionKey");
        if (key instanceof String subscriptionKey) {
            Set<WebSocketSession> sessions = subscriptions.get(subscriptionKey);
            if (sessions != null) {
                sessions.remove(session);
            }
        }
    }

    public void broadcast(UUID merchantId, UUID groupId, Object payload) {
        Set<WebSocketSession> sessions = subscriptions.get(key(merchantId, groupId));
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException ex) {
                LOG.warn("websocket_broadcast_failed session={}", session.getId());
            }
        }
    }

    private static String key(UUID merchantId, UUID groupId) {
        return merchantId + ":" + groupId;
    }
}
