package com.craftpilot.runtime.ticket;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.events.OutboxWriter;
import com.craftpilot.runtime.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final SkillRegistry skills;

    public AssignmentService(JdbcClient jdbc, OutboxWriter outbox, SkillRegistry skills) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.skills = skills;
    }

    @Transactional
    public UUID allocate(UUID merchant, UUID surveyId, UUID ticketId, String eventId) {
        return allocate(merchant, surveyId, ticketId, eventId, "launch-research-r3");
    }

    @Transactional
    public UUID allocate(UUID merchant, UUID surveyId, UUID ticketId, String eventId, String workflowId) {
        var active = jdbc.sql("""
                SELECT id FROM assignment
                WHERE merchant_id = :m AND ticket_id = :t AND status IN ('READY','CLAIMED')
                """).param("m", merchant).param("t", ticketId).query(UUID.class).optional();
        if (active.isPresent()) {
            return active.get();
        }
        String workflow = workflowId == null || workflowId.isBlank() ? "launch-research-r3" : workflowId;
        if (!List.of("launch-research-r3", "launch-supervise-r4").contains(workflow)) {
            throw ApiFailure.invalid("Unsupported workflow");
        }
        boolean supervise = "launch-supervise-r4".equals(workflow);
        String agentSpec = supervise ? "supervisor-agent-r4" : "research-agent-r3";
        String logicalKey = supervise ? "supervisor-main" : "research-main";
        String nodeId = supervise ? "plan" : "research";
        Integer attemptNo = jdbc.sql("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM agent_run_attempt
                WHERE merchant_id = :m AND ticket_id = :t
                """).param("m", merchant).param("t", ticketId).query(Integer.class).single();
        UUID assignmentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO assignment (merchant_id, id, survey_id, ticket_id, status)
                VALUES (:m, :id, :survey, :ticket, 'READY')
                """).param("m", merchant).param("id", assignmentId).param("survey", surveyId)
                .param("ticket", ticketId).update();
        jdbc.sql("""
                INSERT INTO agent_run_attempt
                (merchant_id, id, survey_id, ticket_id, assignment_id, attempt_no, status)
                VALUES (:m, :id, :survey, :ticket, :a, :n, 'PENDING')
                """).param("m", merchant).param("id", attemptId).param("survey", surveyId)
                .param("ticket", ticketId).param("a", assignmentId).param("n", attemptNo).update();
        jdbc.sql("""
                INSERT INTO agent_run
                (merchant_id, id, survey_id, ticket_id, attempt_id, workflow_id, workflow_version,
                 agent_spec_id, model_profile_id, status, execution_deadline, current_node,
                 max_turns, max_tool_calls, token_budget, cost_budget_usd)
                SELECT :m, :id, :survey, :ticket, :attempt, :wf, wr.version,
                       :agent, 'chat-default-r3', 'QUEUED',
                       clock_timestamp() + interval '20 minutes', :node,
                       a.max_turns, a.max_tool_calls, :tokens, 1.000000
                FROM workflow_release wr, agent_spec a
                WHERE wr.id = :wf AND a.id = :agent
                """).param("m", merchant).param("id", runId).param("survey", surveyId)
                .param("ticket", ticketId).param("attempt", attemptId).param("wf", workflow)
                .param("agent", agentSpec).param("node", nodeId)
                .param("tokens", supervise ? 16000 : 8000).update();
        jdbc.sql("""
                INSERT INTO agent_task
                (merchant_id, id, survey_id, run_id, logical_key, node_id, agent_spec_id, status)
                VALUES (:m, :id, :survey, :run, :key, :node, :agent, 'READY')
                """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                .param("run", runId).param("key", logicalKey).param("node", nodeId)
                .param("agent", agentSpec).update();
        if (supervise) {
            skills.snapshotForRun(merchant, surveyId, runId, workflow, "1", List.of(
                    Map.of("id", "supervisor-skill-r4", "version", "1"),
                    Map.of("id", "evidence-worker-skill-r4", "version", "1"),
                    Map.of("id", "material-worker-skill-r4", "version", "1")));
        }
        jdbc.sql("""
                UPDATE ticket SET status = 'RUNNING', updated_at = clock_timestamp(), version = version + 1
                WHERE merchant_id = :m AND id = :t
                """).param("m", merchant).param("t", ticketId).update();
        outbox.enqueue(merchant, "agent_run", runId, "RUNTIME_RUN_QUEUED", Map.of(
                "runId", runId,
                "ticketId", ticketId,
                "surveyId", surveyId,
                "eventId", eventId == null ? "" : eventId));
        return assignmentId;
    }
}
