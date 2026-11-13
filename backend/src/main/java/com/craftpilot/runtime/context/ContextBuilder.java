package com.craftpilot.runtime.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContextBuilder {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ContextBuilder(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public UUID persist(UUID merchant, UUID surveyId, UUID runId, UUID taskId, String instructions,
                        List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas,
                        List<UUID> evidenceRefs, int tokenEstimate, String omitted) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO context_snapshot
                    (merchant_id, id, survey_id, run_id, task_id, instruction_version, messages_json,
                     tool_schemas_json, evidence_refs_json, token_estimate, omitted_reason)
                    VALUES (:m, :id, :survey, :run, :task, 'r3-ctx-1', CAST(:messages AS jsonb),
                            CAST(:tools AS jsonb), CAST(:evidence AS jsonb), :tokens, :omitted)
                    """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                    .param("task", taskId).param("messages", mapper.writeValueAsString(messages))
                    .param("tools", mapper.writeValueAsString(toolSchemas))
                    .param("evidence", mapper.writeValueAsString(evidenceRefs))
                    .param("tokens", tokenEstimate).param("omitted", omitted == null ? "" : omitted).update();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return id;
    }

    public List<Map<String, Object>> buildMessages(UUID merchant, UUID surveyId, UUID runId, UUID ticketId,
                                                  String instructions, String objective) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", instructions));
        String summary = authorizedSummary(merchant, surveyId, ticketId, objective);
        messages.add(Map.of("role", "user", "content", summary));

        List<Map<String, Object>> history = jdbc.sql("""
                SELECT turn_no, response_summary, tool_batch_json::text AS tools
                FROM agent_turn
                WHERE merchant_id = :m AND run_id = :run AND status = 'RESPONSE_COMMITTED'
                ORDER BY turn_no
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("turn", rs.getInt("turn_no"));
                    row.put("response", rs.getString("response_summary"));
                    row.put("tools", rs.getString("tools"));
                    return row;
                }).list();

        // keep last 6 turns under budget
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            Map<String, Object> turn = history.get(i);
            String response = String.valueOf(turn.get("response"));
            String toolsJson = String.valueOf(turn.get("tools"));
            List<Map<String, Object>> toolResults = loadToolResults(merchant, runId, (Integer) turn.get("turn"));
            if (toolsJson != null && toolsJson.startsWith("[")) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> batch = mapper.readValue(toolsJson, List.class);
                    if (!batch.isEmpty()) {
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        for (Map<String, Object> call : batch) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", call.get("name"));
                            fn.put("arguments", call.get("arguments"));
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", call.get("id"));
                            tc.put("type", "function");
                            tc.put("function", fn);
                            toolCalls.add(tc);
                        }
                        Map<String, Object> assistant = new LinkedHashMap<>();
                        assistant.put("role", "assistant");
                        if (response != null && !response.isBlank() && !"null".equals(response)) {
                            assistant.put("content", response);
                        }
                        assistant.put("tool_calls", toolCalls);
                        messages.add(assistant);
                    }
                } catch (Exception ignored) {
                    if (response != null && !response.isBlank() && !"null".equals(response)) {
                        messages.add(Map.of("role", "assistant", "content", response));
                    }
                }
            } else if (response != null && !response.isBlank() && !"null".equals(response)) {
                messages.add(Map.of("role", "assistant", "content", response));
            }
            for (Map<String, Object> tr : toolResults) {
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tr.get("providerCallId"),
                        "content", String.valueOf(tr.get("content"))));
            }
        }
        return messages;
    }

    private String authorizedSummary(UUID merchant, UUID surveyId, UUID ticketId, String objective) {
        List<UUID> revisions = jdbc.sql("""
                SELECT revision_id FROM ticket_revision
                WHERE merchant_id = :m AND ticket_id = :t ORDER BY revision_id
                """).param("m", merchant).param("t", ticketId).query(UUID.class).list();
        long evidence = jdbc.sql("""
                SELECT COUNT(*) FROM evidence_ref
                WHERE merchant_id = :m AND survey_id = :survey AND visibility = 'ACTIVE'
                """).param("m", merchant).param("survey", surveyId).query(Long.class).single();
        long issues = jdbc.sql("""
                SELECT COUNT(*) FROM feedback_issue
                WHERE merchant_id = :m AND survey_id = :survey AND status = 'OPEN'
                """).param("m", merchant).param("survey", surveyId).query(Long.class).single();
        return "Survey " + surveyId + " objective: " + objective
                + ". Authorized revisions: " + revisions
                + ". Active evidence count: " + evidence
                + ". Open feedback issues: " + issues
                + ". Use tools to inspect evidence before concluding. Cite only verified evidence IDs.";
    }

    private List<Map<String, Object>> loadToolResults(UUID merchant, UUID runId, int turnNo) {
        UUID turnId = jdbc.sql("""
                SELECT id FROM agent_turn WHERE merchant_id = :m AND run_id = :run AND turn_no = :n
                """).param("m", merchant).param("run", runId).param("n", turnNo).query(UUID.class)
                .optional().orElse(null);
        if (turnId == null) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT ti.ordinal, ti.result_json::text AS body, d.remote_request_id
                FROM tool_invocation ti
                LEFT JOIN tool_dispatch_attempt d ON d.merchant_id = ti.merchant_id AND d.invocation_id = ti.id
                WHERE ti.merchant_id = :m AND ti.run_id = :run AND ti.turn_id = :turn
                  AND ti.status IN ('SUCCEEDED', 'FAILED_FINAL')
                ORDER BY ti.ordinal
                """).param("m", merchant).param("run", runId).param("turn", turnId)
                .query((rs, i) -> Map.<String, Object>of(
                        "providerCallId", rs.getString("remote_request_id") == null
                                ? "tool-" + rs.getInt("ordinal") : rs.getString("remote_request_id"),
                        "content", rs.getString("body") == null ? "{}" : rs.getString("body")))
                .list();
    }
}
