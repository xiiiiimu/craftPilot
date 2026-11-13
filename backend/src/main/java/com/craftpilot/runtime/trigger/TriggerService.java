package com.craftpilot.runtime.trigger;

import com.craftpilot.common.ApiFailure;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriggerService {
    public static final String RULE_VERSION = "r3-trigger-1";

    private final JdbcClient jdbc;

    public TriggerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID acceptManual(UUID actor, UUID merchant, UUID surveyId, UUID groupId, List<UUID> revisionIds,
                             String objective, String sourceKey) {
        return accept(merchant, surveyId, "MANUAL", sourceKey, null, revisionIds, objective, actor);
    }

    @Transactional
    public UUID accept(UUID merchant, UUID surveyId, String sourceType, String sourceKey, UUID revisionHint,
                       List<UUID> revisionIds, String objective, UUID actor) {
        var existing = jdbc.sql("""
                SELECT id FROM source_event
                WHERE merchant_id = :m AND survey_id = :survey AND source_type = :type AND source_key = :key
                """).param("m", merchant).param("survey", surveyId).param("type", sourceType)
                .param("key", sourceKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            UUID priorTicket = jdbc.sql("""
                    SELECT ticket_id FROM trigger_evaluation
                    WHERE merchant_id = :m AND source_event_id = :e AND ticket_id IS NOT NULL
                    ORDER BY created_at DESC LIMIT 1
                    """).param("m", merchant).param("e", existing.get()).query(UUID.class).optional().orElse(null);
            if (priorTicket != null) {
                recordEvaluation(merchant, surveyId, existing.get(), "DEDUPED", priorTicket, "SOURCE_DEDUPED");
                return priorTicket;
            }
        }

        boolean subscribed = jdbc.sql("""
                SELECT 1 FROM subscription
                WHERE merchant_id = :m AND survey_id = :survey AND status = 'ACTIVE'
                  AND (valid_until IS NULL OR valid_until > clock_timestamp())
                """).param("m", merchant).param("survey", surveyId).query(Integer.class).optional().isPresent();
        if (!subscribed && !"MANUAL".equals(sourceType)) {
            UUID eventId = insertSource(merchant, surveyId, sourceType, sourceKey, revisionHint);
            recordEvaluation(merchant, surveyId, eventId, "NO_SUBSCRIPTION", null, "NO_ACTIVE_SUBSCRIPTION");
            throw ApiFailure.conflict("NO_SUBSCRIPTION", "No active subscription for survey");
        }

        boolean open = jdbc.sql("""
                SELECT 1 FROM survey_session
                WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE'
                  AND clock_timestamp() < intake_until
                """).param("m", merchant).param("id", surveyId).query(Integer.class).optional().isPresent();
        if (!open) {
            UUID eventId = insertSource(merchant, surveyId, sourceType, sourceKey, revisionHint);
            recordEvaluation(merchant, surveyId, eventId, "EXPIRED", null, "SURVEY_WINDOW_CLOSED");
            throw ApiFailure.conflict("SURVEY_WINDOW_CLOSED", "Survey intake window is closed");
        }

        List<UUID> revisions = revisionIds == null || revisionIds.isEmpty()
                ? jdbc.sql("""
                        SELECT revision_id FROM survey_revision
                        WHERE merchant_id = :m AND survey_id = :survey ORDER BY revision_id
                        """).param("m", merchant).param("survey", surveyId).query(UUID.class).list()
                : revisionIds;
        if (revisions.isEmpty()) {
            UUID eventId = insertSource(merchant, surveyId, sourceType, sourceKey, revisionHint);
            recordEvaluation(merchant, surveyId, eventId, "INSUFFICIENT_EVIDENCE", null, "NO_REVISIONS");
            throw ApiFailure.invalid("At least one revision is required");
        }
        for (UUID revisionId : revisions) {
            boolean allowed = jdbc.sql("""
                    SELECT 1 FROM survey_revision
                    WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :r
                    """).param("m", merchant).param("survey", surveyId).param("r", revisionId)
                    .query(Integer.class).optional().isPresent();
            if (!allowed) {
                throw ApiFailure.invalid("revisionId is not in survey whitelist");
            }
        }

        UUID eventId = insertSource(merchant, surveyId, sourceType, sourceKey, revisionHint);
        UUID ticketId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ticket
                (merchant_id, id, survey_id, objective, status, source_event_id, deadline_at, created_by)
                VALUES (:m, :id, :survey, :obj, 'CREATED', :event,
                        clock_timestamp() + make_interval(mins => 30), :actor)
                """).param("m", merchant).param("id", ticketId).param("survey", surveyId)
                .param("obj", objective == null || objective.isBlank() ? "Research survey feedback" : objective)
                .param("event", eventId).param("actor", actor).update();
        for (UUID revisionId : revisions) {
            jdbc.sql("""
                    INSERT INTO ticket_revision (merchant_id, ticket_id, survey_id, revision_id)
                    VALUES (:m, :t, :survey, :r)
                    """).param("m", merchant).param("t", ticketId).param("survey", surveyId)
                    .param("r", revisionId).update();
        }
        jdbc.sql("""
                UPDATE ticket SET status = 'QUEUED', updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", ticketId).update();
        recordEvaluation(merchant, surveyId, eventId, "ACCEPTED", ticketId, null);
        return ticketId;
    }

    private UUID insertSource(UUID merchant, UUID surveyId, String sourceType, String sourceKey, UUID revisionId) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO source_event
                    (merchant_id, id, survey_id, source_type, source_key, revision_id, occurred_at)
                    VALUES (:m, :id, :survey, :type, :key, :revision, clock_timestamp())
                    """).param("m", merchant).param("id", id).param("survey", surveyId)
                    .param("type", sourceType).param("key", sourceKey).param("revision", revisionId).update();
            return id;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return jdbc.sql("""
                    SELECT id FROM source_event
                    WHERE merchant_id = :m AND survey_id = :survey AND source_type = :type AND source_key = :key
                    """).param("m", merchant).param("survey", surveyId).param("type", sourceType)
                    .param("key", sourceKey).query(UUID.class).single();
        }
    }

    private void recordEvaluation(UUID merchant, UUID surveyId, UUID eventId, String disposition,
                                  UUID ticketId, String reason) {
        jdbc.sql("""
                INSERT INTO trigger_evaluation
                (merchant_id, id, survey_id, source_event_id, rule_version, disposition, ticket_id, reason_code)
                VALUES (:m, :id, :survey, :event, :rule, :disp, :ticket, :reason)
                """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                .param("event", eventId).param("rule", RULE_VERSION).param("disp", disposition)
                .param("ticket", ticketId).param("reason", reason).update();
    }
}
