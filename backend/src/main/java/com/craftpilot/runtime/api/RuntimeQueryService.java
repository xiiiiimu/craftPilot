package com.craftpilot.runtime.api;

import static com.craftpilot.runtime.RuntimeDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.tenant.TenantAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RuntimeQueryService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final ObjectMapper mapper;

    public RuntimeQueryService(JdbcClient jdbc, TenantAccess tenant, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.mapper = mapper;
    }

    public RunView getRun(UUID actor, UUID merchant, UUID runId) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT id, ticket_id, survey_id, status, current_node, version, checkpoint_head, lease_epoch,
                       execution_deadline, turns_used, tool_calls_used, tokens_used, final_output_json::text AS out
                FROM agent_run WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", runId)
                .query((rs, i) -> new RunView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("ticket_id", UUID.class),
                        rs.getObject("survey_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("current_node"),
                        rs.getLong("version"),
                        rs.getLong("checkpoint_head"),
                        rs.getLong("lease_epoch"),
                        rs.getObject("execution_deadline", java.time.OffsetDateTime.class).toInstant(),
                        rs.getInt("turns_used"),
                        rs.getInt("tool_calls_used"),
                        rs.getInt("tokens_used"),
                        readJson(rs.getString("out")))).single();
    }

    public List<TaskView> listTasks(UUID actor, UUID merchant, UUID runId) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT id, run_id, logical_key, node_id, status
                FROM agent_task WHERE merchant_id = :m AND run_id = :run ORDER BY created_at
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> new TaskView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("logical_key"),
                        rs.getString("node_id"),
                        rs.getString("status"))).list();
    }

    public List<EventView> listEvents(UUID actor, UUID merchant, UUID runId, long afterSeq) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT seq, event_type, task_id, trace_id, created_at, payload_json::text AS payload
                FROM runtime_event
                WHERE merchant_id = :m AND run_id = :run AND seq > :after
                ORDER BY seq
                """).param("m", merchant).param("run", runId).param("after", afterSeq)
                .query((rs, i) -> new EventView(
                        rs.getLong("seq"),
                        rs.getString("event_type"),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("trace_id"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        readJson(rs.getString("payload")))).list();
    }

    public List<ModelCallView> listModelCalls(UUID actor, UUID merchant, UUID runId) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT m.id, m.provider, m.model_name, m.purpose, m.status, m.provider_request_id,
                       m.input_tokens, m.output_tokens
                FROM model_call_attempt m
                JOIN agent_turn t ON t.merchant_id = m.merchant_id AND t.model_call_id = m.id
                WHERE t.merchant_id = :m AND t.run_id = :run
                ORDER BY t.turn_no
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> new ModelCallView(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("model_name"),
                        rs.getString("purpose"),
                        rs.getString("status"),
                        redact(rs.getString("provider_request_id")),
                        (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"))).list();
    }

    public List<ToolCallView> listToolCalls(UUID actor, UUID merchant, UUID runId) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT id, tool_name, status, ordinal, result_json::text AS body
                FROM tool_invocation WHERE merchant_id = :m AND run_id = :run ORDER BY created_at
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> new ToolCallView(
                        rs.getObject("id", UUID.class),
                        rs.getString("tool_name"),
                        rs.getString("status"),
                        rs.getInt("ordinal"),
                        readJson(rs.getString("body")))).list();
    }

    public BudgetView getBudget(UUID actor, UUID merchant, UUID runId) {
        authorizeRun(actor, merchant, runId);
        return jdbc.sql("""
                SELECT id, token_budget, tokens_used, max_turns, turns_used, max_tool_calls, tool_calls_used,
                       cost_budget_usd::text AS cb, cost_used_usd::text AS cu
                FROM agent_run WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", runId)
                .query((rs, i) -> new BudgetView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("token_budget"),
                        rs.getInt("tokens_used"),
                        rs.getInt("max_turns"),
                        rs.getInt("turns_used"),
                        rs.getInt("max_tool_calls"),
                        rs.getInt("tool_calls_used"),
                        rs.getString("cb"),
                        rs.getString("cu"))).single();
    }

    @Transactional
    public RunView cancelRun(UUID actor, UUID merchant, UUID runId, CancelRunRequest request) {
        RunView current = getRun(actor, merchant, runId);
        if (current.version() != request.expectedVersion()) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Run version mismatch");
        }
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED").contains(current.status())) {
            return current;
        }
        jdbc.sql("""
                UPDATE agent_run SET status = 'CANCEL_REQUESTED', lease_epoch = lease_epoch + 1,
                       terminal_reason = :reason, version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND version = :v
                """).param("m", merchant).param("id", runId).param("v", request.expectedVersion())
                .param("reason", request.reason()).update();
        return getRun(actor, merchant, runId);
    }

    private void authorizeRun(UUID actor, UUID merchant, UUID runId) {
        UUID groupId = jdbc.sql("""
                SELECT s.group_id
                FROM agent_run r
                JOIN survey_session s ON s.merchant_id = r.merchant_id AND s.id = r.survey_id
                WHERE r.merchant_id = :m AND r.id = :id
                """).param("m", merchant).param("id", runId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupMember(actor, merchant, groupId);
    }

    private Object readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, Object.class);
        } catch (Exception ex) {
            return Map.of("raw", body);
        }
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 8 ? value.charAt(0) + "…" : value.substring(0, 8) + "…";
    }
}
