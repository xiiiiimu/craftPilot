package com.craftpilot.runtime.tool;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceDtos.EvidenceVerifyRequest;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.retrieval.RetrievalService;
import com.craftpilot.runtime.ledger.BudgetService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ToolBroker {
    private static final Set<String> ALLOWED = Set.of(
            "sample_get_snapshot", "survey_get_stats", "evidence_search", "evidence_verify", "vision_get_comparison");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final BudgetService budget;
    private final EvidenceService evidence;
    private final RetrievalService retrieval;

    public ToolBroker(JdbcClient jdbc, ObjectMapper mapper, BudgetService budget, EvidenceService evidence,
                      RetrievalService retrieval) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.budget = budget;
        this.evidence = evidence;
        this.retrieval = retrieval;
    }

    public List<Map<String, Object>> toolSchemas() {
        return jdbc.sql("""
                SELECT tool_name, input_schema::text AS schema
                FROM tool_release WHERE enabled ORDER BY tool_name
                """).query((rs, i) -> {
            try {
                Map<String, Object> schema = mapper.readValue(rs.getString("schema"), Map.class);
                return Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", rs.getString("tool_name"),
                                "description", "CraftPilot authorized read-only tool",
                                "parameters", schema));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).list();
    }

    @Transactional
    public Map<String, Object> dispatch(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                      UUID turnId, long leaseEpoch, int ordinal, String toolName,
                                      String argumentsJson, String providerCallId) {
        if (!ALLOWED.contains(toolName)) {
            throw ApiFailure.forbidden();
        }
        var run = jdbc.sql("""
                SELECT status, lease_epoch, lease_owner, lease_until, ticket_id
                FROM agent_run WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", runId)
                .query((rs, i) -> Map.of(
                        "status", rs.getString("status"),
                        "epoch", rs.getLong("lease_epoch"),
                        "ticketId", rs.getObject("ticket_id", UUID.class)))
                .optional().orElseThrow(ApiFailure::notFound);
        if (!List.of("RUNNING", "RECOVERING").contains(String.valueOf(run.get("status")))) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Run cannot accept tool results");
        }
        if (((Number) run.get("epoch")).longValue() != leaseEpoch) {
            throw ApiFailure.conflict("LEASE_FENCE", "Stale worker lease epoch");
        }
        var tool = jdbc.sql("""
                SELECT version, effect_class, input_schema::text AS schema
                FROM tool_release WHERE tool_name = :name AND enabled
                """).param("name", toolName)
                .query((rs, i) -> Map.of(
                        "version", rs.getString("version"),
                        "effect", rs.getString("effect_class"),
                        "schema", rs.getString("schema")))
                .optional().orElseThrow(ApiFailure::notFound);
        JsonNode args = parseArgs(argumentsJson);
        validateSchema(String.valueOf(tool.get("schema")), args);

        String opKey = runId + ":" + turnId + ":" + ordinal + ":" + toolName + ":" + sha(argumentsJson);
        var existing = jdbc.sql("""
                SELECT id, status, result_json::text AS body FROM tool_invocation
                WHERE merchant_id = :m AND operation_key = :key
                """).param("m", merchant).param("key", opKey)
                .query((rs, i) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "status", rs.getString("status"),
                        "body", rs.getString("body") == null ? "{}" : rs.getString("body")))
                .optional().orElse(null);
        if (existing != null && "SUCCEEDED".equals(existing.get("status"))) {
            try {
                return mapper.readValue(String.valueOf(existing.get("body")), Map.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        budget.consumeToolSlot(merchant, runId);
        UUID invocationId = existing == null ? UUID.randomUUID() : (UUID) existing.get("id");
        if (existing == null) {
            jdbc.sql("""
                    INSERT INTO tool_invocation
                    (merchant_id, id, survey_id, run_id, task_id, turn_id, ordinal, tool_name, tool_version,
                     arguments_json, arguments_hash, operation_key, effect_class, status, lease_epoch)
                    VALUES (:m, :id, :survey, :run, :task, :turn, :ord, :name, :ver,
                            CAST(:args AS jsonb), :hash, :op, :effect, 'DISPATCHING', :epoch)
                    """).param("m", merchant).param("id", invocationId).param("survey", surveyId)
                    .param("run", runId).param("task", taskId).param("turn", turnId).param("ord", ordinal)
                    .param("name", toolName).param("ver", tool.get("version")).param("args", argumentsJson)
                    .param("hash", sha(argumentsJson)).param("op", opKey).param("effect", tool.get("effect"))
                    .param("epoch", leaseEpoch).update();
        } else {
            jdbc.sql("""
                    UPDATE tool_invocation SET status = 'DISPATCHING', lease_epoch = :epoch
                    WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", invocationId).param("epoch", leaseEpoch).update();
        }
        UUID dispatchId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tool_dispatch_attempt
                (merchant_id, id, invocation_id, attempt_no, lease_epoch, status, remote_request_id)
                VALUES (:m, :id, :inv, 1, :epoch, 'SENT', :remote)
                """).param("m", merchant).param("id", dispatchId).param("inv", invocationId)
                .param("epoch", leaseEpoch).param("remote", providerCallId).update();

        Map<String, Object> result;
        try {
            result = execute(actor, merchant, surveyId, (UUID) run.get("ticketId"), toolName, args);
        } catch (RuntimeException ex) {
            String err = ex instanceof ApiFailure af ? af.code() : ex.getClass().getSimpleName();
            if (err.length() > 60) {
                err = err.substring(0, 60);
            }
            if (ex instanceof ApiFailure af && List.of("LEASE_FENCE", "RUN_NOT_ACTIVE", "MAX_TOOL_CALLS_EXCEEDED")
                    .contains(af.code())) {
                jdbc.sql("""
                        UPDATE tool_invocation SET status = 'FAILED_FINAL', error_code = :err,
                               completed_at = clock_timestamp()
                        WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", invocationId)
                        .param("err", err).update();
                jdbc.sql("""
                        UPDATE tool_dispatch_attempt SET status = 'FAILED', completed_at = clock_timestamp(),
                               error_code = :err WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", dispatchId)
                        .param("err", err).update();
                throw ex;
            }
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("error", err);
            failed.put("tool", toolName);
            try {
                String body = mapper.writeValueAsString(failed);
                jdbc.sql("""
                        UPDATE tool_invocation SET status = 'FAILED_FINAL', error_code = :err,
                               result_json = CAST(:body AS jsonb), completed_at = clock_timestamp()
                        WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", invocationId)
                        .param("err", err).param("body", body).update();
                jdbc.sql("""
                        UPDATE tool_dispatch_attempt SET status = 'FAILED', completed_at = clock_timestamp(),
                               error_code = :err WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", dispatchId)
                        .param("err", err).update();
            } catch (Exception writeEx) {
                throw new IllegalStateException(writeEx);
            }
            return failed;
        }

        try {
            String body = mapper.writeValueAsString(result);
            jdbc.sql("""
                    UPDATE tool_invocation SET status = 'SUCCEEDED', result_json = CAST(:body AS jsonb),
                           completed_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", invocationId).param("body", body).update();
            jdbc.sql("""
                    UPDATE tool_dispatch_attempt SET status = 'SUCCEEDED', completed_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", dispatchId).update();
            jdbc.sql("""
                    INSERT INTO effect_receipt (merchant_id, operation_key, invocation_id, result_hash)
                    VALUES (:m, :op, :inv, :hash)
                    ON CONFLICT DO NOTHING
                    """).param("m", merchant).param("op", opKey).param("inv", invocationId)
                    .param("hash", sha(body)).update();
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> execute(UUID actor, UUID merchant, UUID surveyId, UUID ticketId,
                                     String toolName, JsonNode args) {
        return switch (toolName) {
            case "sample_get_snapshot" -> sampleSnapshot(merchant, surveyId, ticketId, uuid(args, "revisionId"));
            case "survey_get_stats" -> surveyStats(merchant, surveyId, ticketId, uuid(args, "revisionId"));
            case "evidence_search" -> evidenceSearch(actor, merchant, surveyId, ticketId,
                    uuid(args, "revisionId"), text(args, "query"), args.path("topK").asInt(5));
            case "evidence_verify" -> evidenceVerify(actor, merchant, surveyId, uuid(args, "evidenceId"));
            case "vision_get_comparison" -> visionComparison(merchant, surveyId, uuid(args, "comparisonId"));
            default -> throw ApiFailure.forbidden();
        };
    }

    private Map<String, Object> sampleSnapshot(UUID merchant, UUID surveyId, UUID ticketId, UUID revisionId) {
        requireTicketRevision(merchant, ticketId, revisionId);
        var rev = jdbc.sql("""
                SELECT r.id, r.revision_no, r.notes, s.name AS style_name
                FROM sample_revision r
                JOIN candidate_style s ON s.merchant_id = r.merchant_id AND s.id = r.style_id
                WHERE r.merchant_id = :m AND r.id = :id
                """).param("m", merchant).param("id", revisionId)
                .query((rs, i) -> Map.<String, Object>of(
                        "revisionId", rs.getObject("id", UUID.class),
                        "revisionNo", rs.getInt("revision_no"),
                        "styleName", rs.getString("style_name"),
                        "notes", rs.getString("notes") == null ? "" : rs.getString("notes")))
                .optional().orElseThrow(ApiFailure::notFound);
        List<Map<String, Object>> bom = jdbc.sql("""
                SELECT line_no, material_code, material_name, quantity, unit
                FROM bom_item WHERE merchant_id = :m AND revision_id = :r ORDER BY line_no
                """).param("m", merchant).param("r", revisionId)
                .query((rs, i) -> Map.<String, Object>of(
                        "lineNo", rs.getInt("line_no"),
                        "materialCode", rs.getString("material_code"),
                        "materialName", rs.getString("material_name"),
                        "quantity", rs.getBigDecimal("quantity"),
                        "unit", rs.getString("unit"))).list();
        Map<String, Object> out = new LinkedHashMap<>(rev);
        out.put("bom", bom);
        out.put("surveyId", surveyId);
        return out;
    }

    private Map<String, Object> surveyStats(UUID merchant, UUID surveyId, UUID ticketId, UUID revisionId) {
        requireTicketRevision(merchant, ticketId, revisionId);
        long issues = jdbc.sql("""
                SELECT COUNT(*) FROM feedback_issue
                WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :r AND status = 'OPEN'
                """).param("m", merchant).param("survey", surveyId).param("r", revisionId)
                .query(Long.class).single();
        long evidence = jdbc.sql("""
                SELECT COUNT(*) FROM evidence_ref
                WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :r AND visibility = 'ACTIVE'
                """).param("m", merchant).param("survey", surveyId).param("r", revisionId)
                .query(Long.class).single();
        return Map.of("revisionId", revisionId, "openIssues", issues, "activeEvidence", evidence);
    }

    private Map<String, Object> evidenceSearch(UUID actor, UUID merchant, UUID surveyId, UUID ticketId,
                                              UUID revisionId, String query, int topK) {
        requireTicketRevision(merchant, ticketId, revisionId);
        var result = retrieval.search(actor, merchant, surveyId, revisionId, query, topK);
        List<Map<String, Object>> hits = result.hits().stream()
                .map(h -> Map.<String, Object>of(
                        "evidenceId", h.evidenceId(),
                        "chunkId", h.chunkId(),
                        "score", h.score(),
                        "summary", h.summary() == null ? "" : h.summary()))
                .toList();
        return Map.of("retrievalMode", result.retrievalMode(), "hits", hits);
    }

    private Map<String, Object> evidenceVerify(UUID actor, UUID merchant, UUID surveyId, UUID evidenceId) {
        var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(List.of(evidenceId)));
        return Map.of(
                "evidenceId", evidenceId,
                "valid", verified.validIds().contains(evidenceId),
                "validIds", verified.validIds(),
                "invalidIds", verified.invalidIds());
    }

    private Map<String, Object> visionComparison(UUID merchant, UUID surveyId, UUID comparisonId) {
        return jdbc.sql("""
                SELECT id, status, left_revision_id, right_revision_id, evidence_id, gap_code,
                       visible_differences::text AS diffs
                FROM vision_comparison
                WHERE merchant_id = :m AND id = :id AND (survey_id IS NULL OR survey_id = :survey)
                """).param("m", merchant).param("id", comparisonId).param("survey", surveyId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("comparisonId", rs.getObject("id", UUID.class));
                    m.put("status", rs.getString("status"));
                    m.put("leftRevisionId", rs.getObject("left_revision_id", UUID.class));
                    m.put("rightRevisionId", rs.getObject("right_revision_id", UUID.class));
                    m.put("evidenceId", rs.getObject("evidence_id", UUID.class));
                    m.put("gapCode", rs.getString("gap_code"));
                    m.put("visibleDifferences", rs.getString("diffs"));
                    return m;
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    private void requireTicketRevision(UUID merchant, UUID ticketId, UUID revisionId) {
        boolean ok = jdbc.sql("""
                SELECT 1 FROM ticket_revision
                WHERE merchant_id = :m AND ticket_id = :t AND revision_id = :r
                """).param("m", merchant).param("t", ticketId).param("r", revisionId)
                .query(Integer.class).optional().isPresent();
        if (!ok) {
            throw ApiFailure.forbidden();
        }
    }

    private JsonNode parseArgs(String argumentsJson) {
        try {
            return mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception ex) {
            throw ApiFailure.invalid("Tool arguments must be valid JSON");
        }
    }

    private void validateSchema(String schemaJson, JsonNode args) {
        try {
            JsonNode schema = mapper.readTree(schemaJson);
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode req : required) {
                    if (!args.has(req.asText()) || args.get(req.asText()).isNull()) {
                        throw ApiFailure.invalid("Missing required tool argument: " + req.asText());
                    }
                }
            }
            if (!schema.path("additionalProperties").asBoolean(true)) {
                JsonNode props = schema.path("properties");
                for (String name : args.propertyNames()) {
                    if (!props.has(name)) {
                        throw ApiFailure.invalid("Unexpected tool argument: " + name);
                    }
                }
            }
        } catch (ApiFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiFailure.invalid("Invalid tool schema validation");
        }
    }

    private static UUID uuid(JsonNode args, String field) {
        try {
            return UUID.fromString(args.path(field).asText());
        } catch (Exception ex) {
            throw ApiFailure.invalid("Invalid UUID for " + field);
        }
    }

    private static String text(JsonNode args, String field) {
        String v = args.path(field).asText(null);
        if (v == null || v.isBlank()) {
            throw ApiFailure.invalid("Missing " + field);
        }
        return v;
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
