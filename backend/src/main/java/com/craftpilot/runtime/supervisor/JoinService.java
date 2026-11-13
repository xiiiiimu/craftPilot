package com.craftpilot.runtime.supervisor;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import java.time.OffsetDateTime;
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
public class JoinService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CheckpointService checkpoints;

    public JoinService(JdbcClient jdbc, ObjectMapper mapper, CheckpointService checkpoints) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.checkpoints = checkpoints;
    }

    public record JoinView(UUID id, String joinKey, String status, Map<String, Object> result) {}

    @Transactional
    public UUID create(UUID merchant, UUID surveyId, UUID runId, UUID parentTaskId, String joinKey,
                       List<UUID> childTaskIds, List<UUID> handoffIds, OffsetDateTime deadline) {
        var existing = jdbc.sql("""
                SELECT id FROM task_join WHERE merchant_id = :m AND run_id = :run AND join_key = :key
                """).param("m", merchant).param("run", runId).param("key", joinKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID joinId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO task_join
                (merchant_id, id, survey_id, run_id, parent_task_id, join_key, policy, deadline_at, status)
                VALUES (:m, :id, :survey, :run, :parent, :key, 'ALL_REQUIRED', :deadline, 'WAITING')
                """).param("m", merchant).param("id", joinId).param("survey", surveyId).param("run", runId)
                .param("parent", parentTaskId).param("key", joinKey).param("deadline", deadline).update();
        for (int i = 0; i < childTaskIds.size(); i++) {
            UUID child = childTaskIds.get(i);
            UUID handoff = handoffIds != null && i < handoffIds.size() ? handoffIds.get(i) : null;
            jdbc.sql("""
                    INSERT INTO task_join_member
                    (merchant_id, join_id, child_task_id, handoff_id, required, status)
                    VALUES (:m, :join, :child, :handoff, true, 'PENDING')
                    """).param("m", merchant).param("join", joinId).param("child", child)
                    .param("handoff", handoff).update();
        }
        jdbc.sql("""
                UPDATE agent_task SET status = 'BLOCKED' WHERE merchant_id = :m AND id = :id AND status IN ('READY','RUNNING')
                """).param("m", merchant).param("id", parentTaskId).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "JOIN_CREATED", Map.of(
                "joinKey", joinKey, "members", childTaskIds.size()));
        return joinId;
    }

    @Transactional
    public JoinView recordMemberResult(UUID merchant, UUID surveyId, UUID runId, UUID childTaskId,
                                       boolean success, Map<String, Object> result, long resultEpoch) {
        var member = jdbc.sql("""
                SELECT join_id, status, result_epoch FROM task_join_member
                WHERE merchant_id = :m AND child_task_id = :child
                """).param("m", merchant).param("child", childTaskId)
                .query((rs, i) -> Map.of(
                        "joinId", rs.getObject("join_id", UUID.class),
                        "status", rs.getString("status"),
                        "epoch", rs.getLong("result_epoch")))
                .optional().orElse(null);
        if (member == null) {
            throw ApiFailure.notFound();
        }
        UUID joinId = (UUID) member.get("joinId");
        assertParentAccepts(merchant, runId);
        long epoch = ((Number) member.get("epoch")).longValue();
        if (!"PENDING".equals(member.get("status")) && resultEpoch <= epoch) {
            return get(merchant, joinId);
        }
        String status = success ? "SUCCEEDED" : "FAILED";
        jdbc.sql("""
                UPDATE task_join_member
                SET status = :status, result_json = CAST(:body AS jsonb), completed_at = clock_timestamp(),
                    result_epoch = :epoch
                WHERE merchant_id = :m AND join_id = :join AND child_task_id = :child
                  AND (status = 'PENDING' OR result_epoch < :epoch)
                """).param("m", merchant).param("join", joinId).param("child", childTaskId)
                .param("status", status).param("body", write(result)).param("epoch", resultEpoch).update();
        return evaluate(merchant, surveyId, runId, joinId);
    }

    @Transactional
    public JoinView evaluate(UUID merchant, UUID surveyId, UUID runId, UUID joinId) {
        var join = jdbc.sql("""
                SELECT status, deadline_at, parent_task_id, join_key, result_version
                FROM task_join WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", joinId)
                .query((rs, i) -> Map.of(
                        "status", rs.getString("status"),
                        "deadline", rs.getObject("deadline_at", OffsetDateTime.class),
                        "parent", rs.getObject("parent_task_id", UUID.class),
                        "key", rs.getString("join_key"),
                        "version", rs.getLong("result_version")))
                .optional().orElseThrow(ApiFailure::notFound);
        if (!"WAITING".equals(join.get("status"))) {
            return get(merchant, joinId);
        }
        OffsetDateTime deadline = (OffsetDateTime) join.get("deadline");
        if (deadline != null && !deadline.isAfter(OffsetDateTime.now())) {
            jdbc.sql("""
                    UPDATE task_join SET status = 'EXPIRED', completed_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id AND status = 'WAITING'
                    """).param("m", merchant).param("id", joinId).update();
            checkpoints.appendEvent(merchant, surveyId, runId, "JOIN_EXPIRED", Map.of("joinId", joinId));
            return get(merchant, joinId);
        }
        List<Map<String, Object>> members = jdbc.sql("""
                SELECT child_task_id, required, status, result_json::text AS body
                FROM task_join_member WHERE merchant_id = :m AND join_id = :join
                """).param("m", merchant).param("join", joinId)
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("childTaskId", rs.getObject("child_task_id", UUID.class));
                    row.put("required", rs.getBoolean("required"));
                    row.put("status", rs.getString("status"));
                    row.put("body", rs.getString("body"));
                    return row;
                }).list();
        boolean pending = members.stream().anyMatch(m -> "PENDING".equals(m.get("status")));
        boolean requiredFailed = members.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("required"))
                        && List.of("FAILED", "CANCELLED", "EXPIRED").contains(String.valueOf(m.get("status"))));
        if (pending) {
            return get(merchant, joinId);
        }
        String terminal = requiredFailed ? "FAILED" : "COMPLETED";
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        for (Map<String, Object> m : members) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("childTaskId", m.get("childTaskId"));
            out.put("status", m.get("status"));
            out.put("result", m.get("body"));
            outputs.add(out);
            extractEvidence(String.valueOf(m.get("body")), evidenceIds);
        }
        result.put("outputs", outputs);
        result.put("evidenceIds", evidenceIds.stream().distinct().toList());
        result.put("gaps", requiredFailed ? List.of("REQUIRED_MEMBER_FAILED") : List.of());
        jdbc.sql("""
                UPDATE task_join SET status = :status, result_json = CAST(:body AS jsonb),
                       result_version = result_version + 1, completed_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND status = 'WAITING'
                """).param("m", merchant).param("id", joinId).param("status", terminal)
                .param("body", write(result)).update();
        jdbc.sql("""
                UPDATE agent_task SET status = :status, result_json = CAST(:body AS jsonb),
                       finished_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", join.get("parent")).param("status",
                        "COMPLETED".equals(terminal) ? "SUCCEEDED" : "FAILED")
                .param("body", write(result)).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "JOIN_" + terminal, Map.of("joinId", joinId));
        return get(merchant, joinId);
    }

    public JoinView get(UUID merchant, UUID joinId) {
        return jdbc.sql("""
                SELECT id, join_key, status, result_json::text AS body
                FROM task_join WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", joinId)
                .query((rs, i) -> new JoinView(
                        rs.getObject("id", UUID.class),
                        rs.getString("join_key"),
                        rs.getString("status"),
                        readMap(rs.getString("body"))))
                .single();
    }

    private void assertParentAccepts(UUID merchant, UUID runId) {
        String status = jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(String.class).single();
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED", "EXPIRED").contains(status)) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Cancelled run rejects join results");
        }
    }

    private void extractEvidence(String body, List<String> out) {
        if (body == null || body.isBlank()) {
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(body);
        while (m.find()) {
            out.add(m.group());
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);
            return map;
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
