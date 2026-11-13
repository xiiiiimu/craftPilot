package com.craftpilot.runtime.ticket;

import static com.craftpilot.runtime.RuntimeDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.events.OutboxWriter;
import com.craftpilot.runtime.trigger.TriggerService;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final TriggerService triggers;
    private final AssignmentService assignments;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public TicketService(JdbcClient jdbc, TenantAccess tenant, TriggerService triggers,
                         AssignmentService assignments, OutboxWriter outbox, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.triggers = triggers;
        this.assignments = assignments;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public TicketView createManual(UUID actor, UUID merchant, UUID surveyId, CreateTicketRequest request,
                                   UUID idempotencyKey) {
        UUID groupId = requireSurveyAdmin(actor, merchant, surveyId);
        ensureActiveWindow(merchant, surveyId);
        String hash = sha(surveyId + "|" + request.objective() + "|" + request.revisionIds());
        var existing = replayApiCommand(merchant, actor, "survey_ticket", surveyId, idempotencyKey, hash);
        if (existing != null) {
            return existing;
        }
        UUID sourceKey = idempotencyKey != null ? idempotencyKey : UUID.randomUUID();
        UUID ticketId = triggers.acceptManual(actor, merchant, surveyId, groupId, request.revisionIds(),
                request.objective(), "manual:" + sourceKey);
        assignments.allocate(merchant, surveyId, ticketId, "manual-create");
        TicketView view = get(actor, merchant, ticketId);
        storeApiCommand(merchant, actor, "survey_ticket", surveyId, idempotencyKey, hash, view);
        return view;
    }

    public TicketView get(UUID actor, UUID merchant, UUID ticketId) {
        var row = jdbc.sql("""
                SELECT t.id, t.survey_id, t.status, t.objective, t.deadline_at, t.version, t.created_by,
                       s.group_id
                FROM ticket t
                JOIN survey_session s ON s.merchant_id = t.merchant_id AND s.id = t.survey_id
                WHERE t.merchant_id = :m AND t.id = :id
                """).param("m", merchant).param("id", ticketId)
                .query((rs, i) -> Map.of(
                        "ticketId", rs.getObject("id", UUID.class),
                        "surveyId", rs.getObject("survey_id", UUID.class),
                        "status", rs.getString("status"),
                        "objective", rs.getString("objective"),
                        "deadlineAt", rs.getObject("deadline_at", java.time.OffsetDateTime.class).toInstant(),
                        "version", rs.getLong("version"),
                        "groupId", rs.getObject("group_id", UUID.class)))
                .optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupMember(actor, merchant, (UUID) row.get("groupId"));
        List<UUID> revisions = jdbc.sql("""
                SELECT revision_id FROM ticket_revision
                WHERE merchant_id = :m AND ticket_id = :t ORDER BY revision_id
                """).param("m", merchant).param("t", ticketId).query(UUID.class).list();
        var run = jdbc.sql("""
                SELECT id, status FROM agent_run
                WHERE merchant_id = :m AND ticket_id = :t
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchant).param("t", ticketId)
                .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional().orElse(null);
        return new TicketView((UUID) row.get("ticketId"), (UUID) row.get("surveyId"),
                (String) row.get("status"), (String) row.get("objective"),
                (java.time.Instant) row.get("deadlineAt"), (Long) row.get("version"),
                run == null ? null : run.getKey(), run == null ? null : run.getValue(), revisions);
    }

    @Transactional
    public TicketView cancel(UUID actor, UUID merchant, UUID ticketId, CancelTicketRequest request,
                             UUID idempotencyKey) {
        TicketView current = get(actor, merchant, ticketId);
        requireSurveyAdmin(actor, merchant, current.surveyId());
        String hash = sha(ticketId + "|" + request.expectedVersion() + "|" + request.reason());
        var replay = replayApiCommand(merchant, actor, "ticket_cancel", ticketId, idempotencyKey, hash);
        if (replay != null) {
            return replay;
        }
        if (List.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED").contains(current.status())) {
            storeApiCommand(merchant, actor, "ticket_cancel", ticketId, idempotencyKey, hash, current);
            return current;
        }
        if (current.version() != request.expectedVersion()) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Ticket version mismatch");
        }
        jdbc.sql("""
                UPDATE ticket SET status = 'CANCELLED', version = version + 1, terminal_reason = :reason,
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND version = :v
                """).param("m", merchant).param("id", ticketId).param("v", request.expectedVersion())
                .param("reason", request.reason()).update();
        jdbc.sql("""
                UPDATE assignment SET status = 'CANCELLED', finished_at = clock_timestamp(),
                       terminal_reason = :reason
                WHERE merchant_id = :m AND ticket_id = :t AND status IN ('READY','CLAIMED')
                """).param("m", merchant).param("t", ticketId).param("reason", request.reason()).update();
        jdbc.sql("""
                UPDATE agent_run_attempt SET status = 'CANCELLED', updated_at = clock_timestamp()
                WHERE merchant_id = :m AND ticket_id = :t AND status IN ('PENDING','EXECUTING')
                """).param("m", merchant).param("t", ticketId).update();
        jdbc.sql("""
                UPDATE agent_run SET status = 'CANCEL_REQUESTED', lease_epoch = lease_epoch + 1,
                       terminal_reason = :reason, version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND ticket_id = :t
                  AND status IN ('QUEUED','RUNNING','RECOVERING','WAITING_APPROVAL','PAUSED')
                """).param("m", merchant).param("t", ticketId).param("reason", request.reason()).update();
        outbox.enqueue(merchant, "ticket", ticketId, "TICKET_CANCELLED",
                Map.of("ticketId", ticketId, "reason", request.reason()));
        TicketView view = get(actor, merchant, ticketId);
        storeApiCommand(merchant, actor, "ticket_cancel", ticketId, idempotencyKey, hash, view);
        return view;
    }

    private UUID requireSurveyAdmin(UUID actor, UUID merchant, UUID surveyId) {
        UUID groupId = jdbc.sql("SELECT group_id FROM survey_session WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", surveyId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
        return groupId;
    }

    private void ensureActiveWindow(UUID merchant, UUID surveyId) {
        boolean ok = jdbc.sql("""
                SELECT 1 FROM survey_session
                WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE'
                  AND clock_timestamp() < intake_until
                """).param("m", merchant).param("id", surveyId).query(Integer.class).optional().isPresent();
        if (!ok) {
            throw ApiFailure.conflict("SURVEY_WINDOW_CLOSED", "Survey intake window is closed");
        }
    }

    private TicketView replayApiCommand(UUID merchant, UUID actor, String type, UUID resource,
                                        UUID key, String hash) {
        if (key == null) {
            return null;
        }
        return jdbc.sql("""
                SELECT request_hash, response_json::text AS body
                FROM api_command
                WHERE merchant_id = :m AND actor_id = :a AND resource_type = :type
                  AND resource_id = :r AND command_key = :k
                """).param("m", merchant).param("a", actor).param("type", type)
                .param("r", resource).param("k", key)
                .query((rs, i) -> {
                    if (!hash.equals(rs.getString("request_hash"))) {
                        throw ApiFailure.idempotencyConflict();
                    }
                    try {
                        return mapper.readValue(rs.getString("body"), TicketView.class);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }).optional().orElse(null);
    }

    private void storeApiCommand(UUID merchant, UUID actor, String type, UUID resource, UUID key,
                                 String hash, TicketView view) {
        if (key == null) {
            return;
        }
        try {
            jdbc.sql("""
                    INSERT INTO api_command
                    (merchant_id, actor_id, resource_type, resource_id, command_key, request_hash, response_json)
                    VALUES (:m, :a, :type, :r, :k, :hash, CAST(:body AS jsonb))
                    ON CONFLICT DO NOTHING
                    """).param("m", merchant).param("a", actor).param("type", type).param("r", resource)
                    .param("k", key).param("hash", hash).param("body", mapper.writeValueAsString(view)).update();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
