package com.craftpilot.runtime.hitl;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApprovalService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TenantAccess tenant;
    private final CheckpointService checkpoints;

    public ApprovalService(JdbcClient jdbc, ObjectMapper mapper, TenantAccess tenant,
                           CheckpointService checkpoints) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tenant = tenant;
        this.checkpoints = checkpoints;
    }

    public record ApprovalView(UUID id, String status, String kind, String operationDigest, long version,
                               OffsetDateTime expiresAt) {}

    @Transactional
    public ApprovalView request(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                String kind, Map<String, Object> operation, OffsetDateTime expiresAt) {
        tenant.requireOwner(actor, merchant);
        var run = jdbc.sql("""
                SELECT status, checkpoint_head, version FROM agent_run
                WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", runId)
                .query((rs, i) -> Map.of(
                        "status", rs.getString("status"),
                        "head", rs.getLong("checkpoint_head"),
                        "version", rs.getLong("version")))
                .optional().orElseThrow(ApiFailure::notFound);
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED", "SUCCEEDED", "EXPIRED").contains(run.get("status"))) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Run cannot request approval");
        }
        String digest = sha(kind + "|" + write(operation));
        var existing = jdbc.sql("""
                SELECT id, status, kind, operation_digest, version, expires_at
                FROM approval
                WHERE merchant_id = :m AND run_id = :run AND operation_digest = :digest
                  AND status IN ('PENDING','APPROVED')
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchant).param("run", runId).param("digest", digest)
                .query((rs, i) -> new ApprovalView(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("kind"),
                        rs.getString("operation_digest"), rs.getLong("version"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO approval
                (merchant_id, id, survey_id, run_id, task_id, checkpoint_head, kind, operation_digest,
                 operation_json, requested_by, expires_at, status, version)
                VALUES (:m, :id, :survey, :run, :task, :head, :kind, :digest, CAST(:op AS jsonb),
                        :actor, :exp, 'PENDING', 0)
                """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                .param("task", taskId).param("head", run.get("head")).param("kind", kind)
                .param("digest", digest).param("op", write(operation)).param("actor", actor)
                .param("exp", expiresAt).update();
        jdbc.sql("""
                UPDATE agent_run SET status = 'WAITING_APPROVAL', version = version + 1,
                       lease_until = clock_timestamp() - interval '1 second',
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND status IN ('RUNNING','RECOVERING','PAUSED','QUEUED')
                """).param("m", merchant).param("id", runId).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "APPROVAL_REQUESTED", Map.of(
                "approvalId", id, "kind", kind, "digest", digest));
        return new ApprovalView(id, "PENDING", kind, digest, 0, expiresAt);
    }

    @Transactional
    public ApprovalView decide(UUID actor, UUID merchant, UUID approvalId, boolean approve, String reason,
                               long expectedVersion) {
        tenant.requireOwner(actor, merchant);
        var row = jdbc.sql("""
                SELECT a.id, a.survey_id, a.run_id, a.status, a.kind, a.operation_digest, a.version,
                       a.expires_at, a.operation_json::text AS op, r.status AS run_status
                FROM approval a
                JOIN agent_run r ON r.merchant_id = a.merchant_id AND r.id = a.run_id
                WHERE a.merchant_id = :m AND a.id = :id FOR UPDATE OF a
                """).param("m", merchant).param("id", approvalId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("surveyId", rs.getObject("survey_id", UUID.class));
                    m.put("runId", rs.getObject("run_id", UUID.class));
                    m.put("status", rs.getString("status"));
                    m.put("kind", rs.getString("kind"));
                    m.put("digest", rs.getString("operation_digest"));
                    m.put("version", rs.getLong("version"));
                    m.put("expires", rs.getObject("expires_at", OffsetDateTime.class));
                    m.put("op", rs.getString("op"));
                    m.put("runStatus", rs.getString("run_status"));
                    return m;
                }).optional().orElseThrow(ApiFailure::notFound);
        if (!"PENDING".equals(row.get("status"))) {
            return get(merchant, approvalId);
        }
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED", "EXPIRED").contains(String.valueOf(row.get("runStatus")))) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Late approval cannot restart cancelled run");
        }
        OffsetDateTime expires = (OffsetDateTime) row.get("expires");
        if (expires != null && !expires.isAfter(OffsetDateTime.now())) {
            jdbc.sql("""
                    UPDATE approval SET status = 'EXPIRED', version = version + 1, decided_at = clock_timestamp(),
                           decision_reason = 'EXPIRED'
                    WHERE merchant_id = :m AND id = :id AND status = 'PENDING'
                    """).param("m", merchant).param("id", approvalId).update();
            checkpoints.appendEvent(merchant, (UUID) row.get("surveyId"), (UUID) row.get("runId"),
                    "APPROVAL_EXPIRED", Map.of("approvalId", approvalId));
            return get(merchant, approvalId);
        }
        if (((Number) row.get("version")).longValue() != expectedVersion) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Approval version mismatch");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.sql("""
                UPDATE approval SET status = :status, decision_actor = :actor, decided_at = clock_timestamp(),
                       decision_reason = :reason, version = version + 1
                WHERE merchant_id = :m AND id = :id AND status = 'PENDING' AND version = :v
                """).param("m", merchant).param("id", approvalId).param("status", status)
                .param("actor", actor).param("reason", reason == null ? "" : reason)
                .param("v", expectedVersion).update();
        UUID runId = (UUID) row.get("runId");
        UUID surveyId = (UUID) row.get("surveyId");
        if (!approve) {
            jdbc.sql("""
                    UPDATE agent_run SET status = 'FAILED', terminal_reason = 'APPROVAL_REJECTED',
                           version = version + 1, updated_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id AND status = 'WAITING_APPROVAL'
                    """).param("m", merchant).param("id", runId).update();
        }
        checkpoints.appendEvent(merchant, surveyId, runId, "APPROVAL_" + status, Map.of(
                "approvalId", approvalId, "actor", actor));
        return get(merchant, approvalId);
    }

    @Transactional
    public UUID consumeAndResume(UUID actor, UUID merchant, UUID approvalId, String commandKey) {
        tenant.requireOwner(actor, merchant);
        var approval = jdbc.sql("""
                SELECT id, survey_id, run_id, status, operation_digest, checkpoint_head, version
                FROM approval WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", approvalId)
                .query((rs, i) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "surveyId", rs.getObject("survey_id", UUID.class),
                        "runId", rs.getObject("run_id", UUID.class),
                        "status", rs.getString("status"),
                        "digest", rs.getString("operation_digest"),
                        "head", rs.getLong("checkpoint_head"),
                        "version", rs.getLong("version")))
                .optional().orElseThrow(ApiFailure::notFound);
        if (!"APPROVED".equals(approval.get("status"))) {
            throw ApiFailure.conflict("APPROVAL_NOT_APPROVED", "Approval is not approved");
        }
        UUID runId = (UUID) approval.get("runId");
        UUID surveyId = (UUID) approval.get("surveyId");
        String runStatus = jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", runId).query(String.class).single();
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED", "EXPIRED").contains(runStatus)) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Cannot resume cancelled run");
        }
        var existingResume = jdbc.sql("""
                SELECT id, status FROM resume_command
                WHERE merchant_id = :m AND run_id = :run AND command_key = :key
                """).param("m", merchant).param("run", runId).param("key", commandKey)
                .query((rs, i) -> Map.of("id", rs.getObject("id", UUID.class), "status", rs.getString("status")))
                .optional();
        if (existingResume.isPresent()) {
            return (UUID) existingResume.get().get("id");
        }
        UUID resumeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO resume_command
                (merchant_id, id, survey_id, run_id, command_key, status, expected_checkpoint_head,
                 approval_id, actor_id, intent)
                VALUES (:m, :id, :survey, :run, :key, 'PENDING', :head, :approval, :actor, 'RESUME')
                """).param("m", merchant).param("id", resumeId).param("survey", surveyId).param("run", runId)
                .param("key", commandKey).param("head", approval.get("head")).param("approval", approvalId)
                .param("actor", actor).update();
        jdbc.sql("""
                UPDATE approval SET status = 'CONSUMED', consumed_at = clock_timestamp(), version = version + 1
                WHERE merchant_id = :m AND id = :id AND status = 'APPROVED'
                """).param("m", merchant).param("id", approvalId).update();
        jdbc.sql("""
                UPDATE agent_run SET status = 'QUEUED', version = version + 1,
                       lease_owner = NULL,
                       lease_until = clock_timestamp() - interval '1 second',
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND status = 'WAITING_APPROVAL'
                """).param("m", merchant).param("id", runId).update();
        jdbc.sql("""
                UPDATE resume_command SET status = 'APPLIED', applied_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", resumeId).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "RUN_RESUMED", Map.of(
                "approvalId", approvalId, "resumeId", resumeId));
        return resumeId;
    }

    public ApprovalView get(UUID merchant, UUID approvalId) {
        return jdbc.sql("""
                SELECT id, status, kind, operation_digest, version, expires_at
                FROM approval WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", approvalId)
                .query((rs, i) -> new ApprovalView(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("kind"),
                        rs.getString("operation_digest"), rs.getLong("version"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .single();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
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
