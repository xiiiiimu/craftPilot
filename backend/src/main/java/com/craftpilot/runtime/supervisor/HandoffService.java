package com.craftpilot.runtime.supervisor;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.runtime.skill.SkillRegistry;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class HandoffService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SkillRegistry skills;
    private final CheckpointService checkpoints;

    public HandoffService(JdbcClient jdbc, ObjectMapper mapper, SkillRegistry skills,
                          CheckpointService checkpoints) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.skills = skills;
        this.checkpoints = checkpoints;
    }

    public record HandoffView(UUID id, String handoffKey, UUID childTaskId, String targetRole, String status) {}

    @Transactional
    public HandoffView submit(UUID merchant, UUID surveyId, UUID runId, UUID parentTaskId, String handoffKey,
                              String targetRole, String skillId, String skillVersion, String objective,
                              List<UUID> evidenceScope, int tokenBudget, int toolBudget, OffsetDateTime deadline) {
        var existing = jdbc.sql("""
                SELECT id, child_task_id, target_role, status FROM handoff
                WHERE merchant_id = :m AND run_id = :run AND handoff_key = :key
                """).param("m", merchant).param("run", runId).param("key", handoffKey)
                .query((rs, i) -> new HandoffView(
                        rs.getObject("id", UUID.class),
                        handoffKey,
                        rs.getObject("child_task_id", UUID.class),
                        rs.getString("target_role"),
                        rs.getString("status")))
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        assertRunActive(merchant, runId);
        var frozen = skills.frozenSkill(merchant, runId, targetRole);
        if (!skillId.equals(frozen.get("skillId")) || !skillVersion.equals(frozen.get("version"))) {
            throw ApiFailure.conflict("SKILL_PIN_MISMATCH", "Handoff skill must match run snapshot");
        }
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) frozen.get("toolWhitelist");
        UUID childTaskId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO agent_task
                (merchant_id, id, survey_id, run_id, parent_task_id, logical_key, node_id, agent_spec_id, status, started_at)
                VALUES (:m, :id, :survey, :run, :parent, :key, 'worker', 'research-agent-r3', 'READY', clock_timestamp())
                """).param("m", merchant).param("id", childTaskId).param("survey", surveyId).param("run", runId)
                .param("parent", parentTaskId).param("key", "handoff:" + handoffKey).update();
        UUID handoffId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO handoff
                (merchant_id, id, survey_id, run_id, handoff_key, parent_task_id, child_task_id, target_role,
                 skill_id, skill_version, objective, evidence_scope_json, token_budget, tool_budget, deadline_at, status)
                VALUES (:m, :id, :survey, :run, :key, :parent, :child, :role, :sid, :sver, :obj,
                        CAST(:scope AS jsonb), :tokens, :tools, :deadline, 'SUBMITTED')
                """).param("m", merchant).param("id", handoffId).param("survey", surveyId).param("run", runId)
                .param("key", handoffKey).param("parent", parentTaskId).param("child", childTaskId)
                .param("role", targetRole).param("sid", skillId).param("sver", skillVersion)
                .param("obj", objective).param("scope", write(evidenceScope))
                .param("tokens", tokenBudget).param("tools", toolBudget).param("deadline", deadline).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "HANDOFF_SUBMITTED", Map.of(
                "handoffKey", handoffKey, "role", targetRole, "childTaskId", childTaskId,
                "tools", allowed == null ? List.of() : allowed));
        return new HandoffView(handoffId, handoffKey, childTaskId, targetRole, "SUBMITTED");
    }

    @Transactional
    public void markRunning(UUID merchant, UUID handoffId) {
        jdbc.sql("""
                UPDATE handoff SET status = 'RUNNING' WHERE merchant_id = :m AND id = :id AND status = 'SUBMITTED'
                """).param("m", merchant).param("id", handoffId).update();
        jdbc.sql("""
                UPDATE agent_task SET status = 'RUNNING', started_at = COALESCE(started_at, clock_timestamp())
                WHERE merchant_id = :m AND id = (SELECT child_task_id FROM handoff WHERE merchant_id = :m AND id = :id)
                """).param("m", merchant).param("id", handoffId).update();
    }

    @Transactional
    public void complete(UUID merchant, UUID surveyId, UUID runId, UUID handoffId, boolean success,
                         Map<String, Object> result) {
        String status = success ? "SUCCEEDED" : "FAILED";
        int updated = jdbc.sql("""
                UPDATE handoff SET status = :status, result_json = CAST(:body AS jsonb),
                       finished_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND status IN ('SUBMITTED','RUNNING')
                """).param("m", merchant).param("id", handoffId).param("status", status)
                .param("body", write(result)).update();
        if (updated == 0) {
            return;
        }
        jdbc.sql("""
                UPDATE agent_task SET status = :status, result_json = CAST(:body AS jsonb),
                       finished_at = clock_timestamp()
                WHERE merchant_id = :m AND id = (SELECT child_task_id FROM handoff WHERE merchant_id = :m AND id = :id)
                """).param("m", merchant).param("id", handoffId).param("status", status)
                .param("body", write(result)).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "HANDOFF_" + status, Map.of("handoffId", handoffId));
    }

    public Map<String, Object> load(UUID merchant, UUID handoffId) {
        return jdbc.sql("""
                SELECT id, handoff_key, parent_task_id, child_task_id, target_role, skill_id, skill_version,
                       objective, token_budget, tool_budget, status, result_json::text AS body
                FROM handoff WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", handoffId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("handoffKey", rs.getString("handoff_key"));
                    m.put("parentTaskId", rs.getObject("parent_task_id", UUID.class));
                    m.put("childTaskId", rs.getObject("child_task_id", UUID.class));
                    m.put("targetRole", rs.getString("target_role"));
                    m.put("skillId", rs.getString("skill_id"));
                    m.put("skillVersion", rs.getString("skill_version"));
                    m.put("objective", rs.getString("objective"));
                    m.put("tokenBudget", rs.getInt("token_budget"));
                    m.put("toolBudget", rs.getInt("tool_budget"));
                    m.put("status", rs.getString("status"));
                    m.put("result", rs.getString("body"));
                    return m;
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    private void assertRunActive(UUID merchant, UUID runId) {
        String status = jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(String.class).optional()
                .orElseThrow(ApiFailure::notFound);
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED", "SUCCEEDED", "EXPIRED").contains(status)) {
            throw ApiFailure.conflict("RUN_NOT_ACTIVE", "Run cannot accept handoffs");
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
