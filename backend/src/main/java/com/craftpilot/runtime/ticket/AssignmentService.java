package com.craftpilot.runtime.ticket;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.events.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public AssignmentService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public UUID allocate(UUID merchant, UUID surveyId, UUID ticketId, String eventId) {
        var active = jdbc.sql("""
                SELECT id FROM assignment
                WHERE merchant_id = :m AND ticket_id = :t AND status IN ('READY','CLAIMED')
                """).param("m", merchant).param("t", ticketId).query(UUID.class).optional();
        if (active.isPresent()) {
            return active.get();
        }
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
                 agent_spec_id, model_profile_id, status, execution_deadline,
                 max_turns, max_tool_calls, token_budget, cost_budget_usd)
                SELECT :m, :id, :survey, :ticket, :attempt, 'launch-research-r3', wr.version,
                       'research-agent-r3', 'chat-default-r3', 'QUEUED',
                       clock_timestamp() + interval '20 minutes',
                       a.max_turns, a.max_tool_calls, 8000, 1.000000
                FROM workflow_release wr, agent_spec a
                WHERE wr.id = 'launch-research-r3' AND a.id = 'research-agent-r3'
                """).param("m", merchant).param("id", runId).param("survey", surveyId)
                .param("ticket", ticketId).param("attempt", attemptId).update();
        jdbc.sql("""
                INSERT INTO agent_task
                (merchant_id, id, survey_id, run_id, logical_key, node_id, agent_spec_id, status)
                VALUES (:m, :id, :survey, :run, 'research-main', 'research', 'research-agent-r3', 'READY')
                """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                .param("run", runId).update();
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
