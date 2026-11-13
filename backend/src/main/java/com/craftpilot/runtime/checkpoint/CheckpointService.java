package com.craftpilot.runtime.checkpoint;

import com.craftpilot.common.ApiFailure;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CheckpointService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public CheckpointService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public long append(UUID merchant, UUID surveyId, UUID runId, String transitionKey, String nodeId,
                       String status, Map<String, Object> state) {
        long next = jdbc.sql("""
                SELECT checkpoint_head + 1 FROM agent_run
                WHERE merchant_id = :m AND id = :run FOR UPDATE
                """).param("m", merchant).param("run", runId).query(Long.class)
                .optional().orElseThrow(ApiFailure::notFound);
        try {
            jdbc.sql("""
                    INSERT INTO checkpoint
                    (merchant_id, id, survey_id, run_id, seq, parent_seq, transition_key, node_id, status, state_json)
                    VALUES (:m, :id, :survey, :run, :seq, :parent, :key, :node, :status, CAST(:state AS jsonb))
                    """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                    .param("run", runId).param("seq", next)
                    .param("parent", next > 1 ? next - 1 : null, Types.BIGINT)
                    .param("key", transitionKey).param("node", nodeId).param("status", status)
                    .param("state", mapper.writeValueAsString(state)).update();
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return jdbc.sql("""
                    SELECT seq FROM checkpoint WHERE merchant_id = :m AND run_id = :run AND transition_key = :key
                    """).param("m", merchant).param("run", runId).param("key", transitionKey)
                    .query(Long.class).single();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        jdbc.sql("""
                UPDATE agent_run SET checkpoint_head = :seq, current_node = :node, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run
                """).param("m", merchant).param("run", runId).param("seq", next).param("node", nodeId).update();
        appendEvent(merchant, surveyId, runId, "CHECKPOINT", Map.of(
                "seq", next, "transitionKey", transitionKey, "nodeId", nodeId, "status", status));
        return next;
    }

    @Transactional
    public void appendEvent(UUID merchant, UUID surveyId, UUID runId, String type, Map<String, Object> payload) {
        Long seq = jdbc.sql("""
                SELECT COALESCE(MAX(seq), 0) + 1 FROM runtime_event
                WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", runId).query(Long.class).single();
        try {
            jdbc.sql("""
                    INSERT INTO runtime_event
                    (merchant_id, id, survey_id, run_id, seq, event_type, payload_json)
                    VALUES (:m, :id, :survey, :run, :seq, :type, CAST(:payload AS jsonb))
                    """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                    .param("run", runId).param("seq", seq).param("type", type)
                    .param("payload", mapper.writeValueAsString(payload)).update();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
