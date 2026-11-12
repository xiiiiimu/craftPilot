package com.craftpilot.events;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final GroupRealtimeHub hub;
    private final String workerId;
    private final Duration leaseTtl;
    private final int maxAttempts;
    private final boolean dispatchEnabled;

    public OutboxDispatcher(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper, GroupRealtimeHub hub,
                            @Value("${craftpilot.outbox.worker-id:}") String configuredWorkerId,
                            @Value("${craftpilot.outbox.lease-seconds:30}") long leaseSeconds,
                            @Value("${craftpilot.outbox.max-attempts:8}") int maxAttempts,
                            @Value("${craftpilot.outbox.dispatch-enabled:true}") boolean dispatchEnabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.hub = hub;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "worker-" + UUID.randomUUID()
                : configuredWorkerId;
        this.leaseTtl = Duration.ofSeconds(leaseSeconds);
        this.maxAttempts = maxAttempts;
        this.dispatchEnabled = dispatchEnabled;
    }

    @Scheduled(fixedDelayString = "${craftpilot.outbox.poll-interval-ms:500}")
    public void poll() {
        if (!dispatchEnabled) {
            return;
        }
        try {
            List<Claim> claimed = tx.execute(status -> claimBatch(10));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            for (Claim claim : claimed) {
                try {
                    deliver(claim);
                    ack(claim, true, null);
                } catch (Exception ex) {
                    LOG.warn("outbox_delivery_failed event={} type={}", claim.id(), claim.eventType());
                    ack(claim, false, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            LOG.debug("outbox_poll_skipped type={}", ex.getClass().getSimpleName());
        }
    }

    public int dispatchOnce() {
        List<Claim> claimed = tx.execute(status -> claimBatch(20));
        if (claimed == null) {
            return 0;
        }
        int done = 0;
        for (Claim claim : claimed) {
            try {
                deliver(claim);
                ack(claim, true, null);
                done++;
            } catch (Exception ex) {
                ack(claim, false, ex.getMessage());
            }
        }
        return done;
    }

    private List<Claim> claimBatch(int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT id
                    FROM outbox
                    WHERE available_at <= clock_timestamp()
                      AND (
                        status = 'PENDING'
                        OR status = 'FAILED'
                        OR (status = 'IN_FLIGHT' AND lease_until < clock_timestamp())
                      )
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE outbox o
                SET status = 'IN_FLIGHT',
                    lease_owner = :owner,
                    lease_epoch = o.lease_epoch + 1,
                    lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                    attempt_count = o.attempt_count + 1,
                    updated_at = clock_timestamp()
                FROM candidates c
                WHERE o.id = c.id
                RETURNING o.merchant_id, o.id, o.event_type, o.payload_json::text, o.lease_epoch, o.attempt_count
                """)
                .param("limit", limit)
                .param("owner", workerId)
                .param("leaseSeconds", leaseTtl.toSeconds())
                .query((rs, row) -> new Claim(
                        rs.getObject("merchant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getLong("lease_epoch"),
                        rs.getInt("attempt_count")))
                .list();
    }

    private void deliver(Claim claim) throws Exception {
        JsonNode payload = mapper.readTree(claim.payloadJson());
        String effectKey = claim.eventType() + ":" + claim.id();
        boolean already = jdbc.sql("""
                SELECT 1 FROM consumer_receipt
                WHERE merchant_id = :merchant AND consumer_name = 'ws-fanout' AND event_id = :event
                """).param("merchant", claim.merchantId()).param("event", claim.id())
                .query(Integer.class).optional().isPresent();
        if (already) {
            return;
        }

        if ("MESSAGE_PERSISTED".equals(claim.eventType())) {
            UUID groupId = UUID.fromString(payload.get("groupId").asText());
            long groupSeq = payload.get("groupSeq").asLong();
            hub.broadcast(claim.merchantId(), groupId, Map.of(
                    "type", "MESSAGE_HINT",
                    "merchantId", claim.merchantId(),
                    "groupId", groupId,
                    "groupSeq", groupSeq,
                    "messageId", payload.get("messageId").asText()));
        }

        tx.executeWithoutResult(status -> {
            try {
                jdbc.sql("""
                        INSERT INTO consumer_receipt (merchant_id, consumer_name, event_id, effect_key)
                        VALUES (:merchant, 'ws-fanout', :event, :effect)
                        """).param("merchant", claim.merchantId()).param("event", claim.id())
                        .param("effect", effectKey).update();
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // idempotent consumer
            }
        });
    }

    private void ack(Claim claim, boolean success, String error) {
        tx.executeWithoutResult(status -> {
            if (success) {
                jdbc.sql("""
                        UPDATE outbox
                        SET status = 'DELIVERED', lease_until = NULL, last_error = NULL, updated_at = clock_timestamp()
                        WHERE id = :id AND lease_owner = :owner AND lease_epoch = :epoch AND status = 'IN_FLIGHT'
                        """).param("id", claim.id()).param("owner", workerId).param("epoch", claim.leaseEpoch())
                        .update();
                return;
            }
            if (claim.attemptCount() >= maxAttempts) {
                jdbc.sql("""
                        UPDATE outbox
                        SET status = 'DLQ', last_error = :error, lease_until = NULL, updated_at = clock_timestamp()
                        WHERE id = :id AND lease_owner = :owner AND lease_epoch = :epoch AND status = 'IN_FLIGHT'
                        """).param("id", claim.id()).param("owner", workerId).param("epoch", claim.leaseEpoch())
                        .param("error", truncate(error)).update();
                return;
            }
            long backoffSeconds = Math.min(60, 1L << Math.min(claim.attemptCount(), 5));
            jdbc.sql("""
                    UPDATE outbox
                    SET status = 'FAILED',
                        last_error = :error,
                        available_at = clock_timestamp() + make_interval(secs => :backoff),
                        lease_until = NULL,
                        updated_at = clock_timestamp()
                    WHERE id = :id AND lease_owner = :owner AND lease_epoch = :epoch AND status = 'IN_FLIGHT'
                    """).param("id", claim.id()).param("owner", workerId).param("epoch", claim.leaseEpoch())
                    .param("error", truncate(error)).param("backoff", backoffSeconds).update();
        });
    }

    private static String truncate(String error) {
        if (error == null) {
            return "unknown";
        }
        return error.length() <= 400 ? error : error.substring(0, 400);
    }

    private record Claim(UUID merchantId, UUID id, String eventType, String payloadJson, long leaseEpoch,
                         int attemptCount) {}
}
