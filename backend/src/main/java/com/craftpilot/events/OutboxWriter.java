package com.craftpilot.events;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxWriter {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID enqueue(UUID merchant, String aggregateType, UUID aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize outbox payload", ex);
        }
        jdbc.sql("""
                INSERT INTO outbox (merchant_id, id, aggregate_type, aggregate_id, event_type, payload_json, status)
                VALUES (:merchant, :id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), 'PENDING')
                """).param("merchant", merchant).param("id", id).param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId).param("eventType", eventType).param("payload", json).update();
        return id;
    }
}
