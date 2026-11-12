package com.craftpilot.survey;

import static com.craftpilot.survey.SurveyDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.events.OutboxWriter;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SurveyService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public SurveyService(JdbcClient jdbc, TenantAccess tenant, OutboxWriter outbox, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public SurveyView openSurvey(UUID actor, UUID merchant, UUID groupId, CreateSurveyRequest request) {
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
        tenant.lockGroup(merchant, groupId);
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
        advanceDueSurveys(merchant, groupId);

        var open = jdbc.sql("""
                SELECT id FROM survey_session
                WHERE merchant_id = :merchant AND group_id = :group AND status IN ('ACTIVE', 'CLOSING')
                """).param("merchant", merchant).param("group", groupId).query(UUID.class).optional();
        if (open.isPresent()) {
            throw ApiFailure.conflict("SURVEY_ALREADY_OPEN", "Group already has an open survey");
        }

        var revisionIds = request.revisionIds().stream().distinct().toList();
        for (UUID revisionId : revisionIds) {
            jdbc.sql("""
                    SELECT id FROM sample_revision WHERE merchant_id = :merchant AND id = :id
                    """).param("merchant", merchant).param("id", revisionId)
                    .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        }

        UUID surveyId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO survey_session (
                        merchant_id, group_id, id, status, starts_at, planned_ends_at, intake_until,
                        closing_deadline_at, created_by)
                    VALUES (
                        :merchant, :group, :id, 'ACTIVE',
                        clock_timestamp(),
                        clock_timestamp() + interval '48 hours',
                        clock_timestamp() + interval '48 hours',
                        clock_timestamp() + interval '48 hours' + interval '10 minutes',
                        :actor)
                    """).param("merchant", merchant).param("group", groupId).param("id", surveyId)
                    .param("actor", actor).update();
        } catch (DuplicateKeyException ex) {
            throw ApiFailure.conflict("SURVEY_ALREADY_OPEN", "Group already has an open survey");
        }

        for (UUID revisionId : revisionIds) {
            jdbc.sql("""
                    INSERT INTO survey_revision (merchant_id, survey_id, revision_id)
                    VALUES (:merchant, :survey, :revision)
                    """).param("merchant", merchant).param("survey", surveyId).param("revision", revisionId).update();
        }
        jdbc.sql("""
                INSERT INTO subscription (merchant_id, id, survey_id, scope_key, rule_version, status, valid_until)
                VALUES (:merchant, :id, :survey, 'default', 'r1', 'ACTIVE',
                        (SELECT intake_until FROM survey_session WHERE merchant_id = :merchant AND id = :survey))
                """).param("merchant", merchant).param("id", UUID.randomUUID()).param("survey", surveyId).update();

        SurveyView view = getSurvey(actor, merchant, surveyId);
        outbox.enqueue(merchant, "survey_session", surveyId, "SURVEY_OPENED", Map.of(
                "merchantId", merchant, "groupId", groupId, "surveyId", surveyId, "status", view.status()));
        return view;
    }

    @Transactional
    public SurveyView closeSurvey(UUID actor, UUID merchant, UUID surveyId, UUID commandKey) {
        SurveyView current = peekSurvey(merchant, surveyId);
        tenant.requireGroupAdminOrOwner(actor, merchant, current.groupId());
        tenant.lockGroup(merchant, current.groupId());
        jdbc.sql("""
                SELECT id FROM survey_session WHERE merchant_id = :merchant AND id = :id FOR UPDATE
                """).param("merchant", merchant).param("id", surveyId)
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupAdminOrOwner(actor, merchant, current.groupId());

        String requestHash = sha("close:" + surveyId);
        var prior = jdbc.sql("""
                SELECT request_hash, response_json FROM api_command
                WHERE merchant_id = :merchant AND actor_id = :actor AND resource_type = 'survey_close'
                  AND resource_id = :survey AND command_key = :key
                """).param("merchant", merchant).param("actor", actor).param("survey", surveyId)
                .param("key", commandKey)
                .query((rs, row) -> Map.entry(rs.getString("request_hash"), rs.getString("response_json")))
                .optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(requestHash)) {
                throw ApiFailure.idempotencyConflict();
            }
            try {
                return mapper.readValue(prior.get().getValue(), SurveyView.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        advanceDueSurveys(merchant, current.groupId());
        SurveyView afterAdvance = getSurvey(actor, merchant, surveyId);
        if ("CLOSED".equals(afterAdvance.status()) || "CLOSING".equals(afterAdvance.status())) {
            storeCommand(merchant, actor, surveyId, commandKey, requestHash, afterAdvance);
            return afterAdvance;
        }
        if (!"ACTIVE".equals(afterAdvance.status())) {
            throw ApiFailure.conflict("SURVEY_STATE_CONFLICT", "Survey cannot be closed from current state");
        }

        jdbc.sql("""
                UPDATE survey_session
                SET intake_until = LEAST(clock_timestamp(), planned_ends_at),
                    closing_deadline_at = LEAST(clock_timestamp(), planned_ends_at) + interval '10 minutes',
                    status = 'CLOSING',
                    closing_started_at = clock_timestamp(),
                    evidence_watermark = evidence_seq,
                    version = version + 1,
                    updated_at = clock_timestamp()
                WHERE merchant_id = :merchant AND id = :id AND status = 'ACTIVE'
                """).param("merchant", merchant).param("id", surveyId).update();
        revokeSubscriptions(merchant, surveyId, "REVOKED");
        maybeClose(merchant, surveyId);
        SurveyView closed = getSurvey(actor, merchant, surveyId);
        outbox.enqueue(merchant, "survey_session", surveyId, "SURVEY_CLOSED", Map.of(
                "merchantId", merchant, "groupId", closed.groupId(), "surveyId", surveyId,
                "status", closed.status()));
        storeCommand(merchant, actor, surveyId, commandKey, requestHash, closed);
        return closed;
    }

    @Transactional
    public PollView createPoll(UUID actor, UUID merchant, UUID surveyId, CreatePollRequest request) {
        SurveyView survey = getSurvey(actor, merchant, surveyId);
        tenant.requireGroupAdminOrOwner(actor, merchant, survey.groupId());
        tenant.lockGroup(merchant, survey.groupId());
        jdbc.sql("SELECT id FROM survey_session WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", surveyId).query(UUID.class).single();
        requireActiveIntake(merchant, surveyId);

        String kind = request.kind().trim().toUpperCase();
        if (!kind.equals("PREFERENCE") && !kind.equals("PURCHASE_INTENT")) {
            throw ApiFailure.invalid("Unsupported poll kind");
        }
        jdbc.sql("""
                SELECT revision_id FROM survey_revision
                WHERE merchant_id = :merchant AND survey_id = :survey AND revision_id = :revision
                """).param("merchant", merchant).param("survey", surveyId).param("revision", request.revisionId())
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);

        UUID pollId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO survey_poll (merchant_id, survey_id, id, revision_id, kind, question)
                VALUES (:merchant, :survey, :id, :revision, :kind, :question)
                """).param("merchant", merchant).param("survey", surveyId).param("id", pollId)
                .param("revision", request.revisionId()).param("kind", kind)
                .param("question", request.question().trim()).update();
        int pos = 0;
        for (String option : request.options()) {
            jdbc.sql("""
                    INSERT INTO survey_poll_option (merchant_id, poll_id, id, label, position)
                    VALUES (:merchant, :poll, :id, :label, :pos)
                    """).param("merchant", merchant).param("poll", pollId).param("id", UUID.randomUUID())
                    .param("label", option.trim()).param("pos", pos++).update();
        }
        return getPoll(actor, merchant, surveyId, pollId);
    }

    @Transactional
    public VoteView vote(UUID actor, UUID merchant, UUID surveyId, UUID pollId, VoteRequest request) {
        SurveyView survey = peekSurvey(merchant, surveyId);
        tenant.requireGroupMember(actor, merchant, survey.groupId());
        tenant.lockGroup(merchant, survey.groupId());
        jdbc.sql("SELECT id FROM survey_session WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", surveyId).query(UUID.class).single();
        tenant.requireGroupMember(actor, merchant, survey.groupId());
        requireActiveIntake(merchant, surveyId);

        jdbc.sql("""
                SELECT id FROM survey_poll
                WHERE merchant_id = :merchant AND survey_id = :survey AND id = :poll
                """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        jdbc.sql("""
                SELECT id FROM survey_poll_option
                WHERE merchant_id = :merchant AND poll_id = :poll AND id = :option
                """).param("merchant", merchant).param("poll", pollId).param("option", request.optionId())
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);

        var existing = jdbc.sql("""
                SELECT option_id, version FROM survey_vote
                WHERE merchant_id = :merchant AND survey_id = :survey AND poll_id = :poll AND member_id = :member
                """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                .param("member", actor)
                .query((rs, row) -> Map.entry(rs.getObject("option_id", UUID.class), rs.getLong("version")))
                .optional();
        if (existing.isPresent()) {
            if (request.expectedVersion() != null && existing.get().getValue() != request.expectedVersion()) {
                throw ApiFailure.conflict("VERSION_CONFLICT", "Vote version mismatch");
            }
            jdbc.sql("""
                    UPDATE survey_vote SET option_id = :option, version = version + 1, updated_at = clock_timestamp()
                    WHERE merchant_id = :merchant AND survey_id = :survey AND poll_id = :poll AND member_id = :member
                    """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                    .param("member", actor).param("option", request.optionId()).update();
        } else {
            try {
                jdbc.sql("""
                        INSERT INTO survey_vote (merchant_id, survey_id, poll_id, member_id, option_id)
                        VALUES (:merchant, :survey, :poll, :member, :option)
                        """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                        .param("member", actor).param("option", request.optionId()).update();
            } catch (DuplicateKeyException ex) {
                return vote(actor, merchant, surveyId, pollId, request);
            }
        }
        return jdbc.sql("""
                SELECT poll_id, option_id, version FROM survey_vote
                WHERE merchant_id = :merchant AND survey_id = :survey AND poll_id = :poll AND member_id = :member
                """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                .param("member", actor)
                .query((rs, row) -> new VoteView(rs.getObject("poll_id", UUID.class),
                        rs.getObject("option_id", UUID.class), rs.getLong("version")))
                .single();
    }

    public PollStatsView stats(UUID actor, UUID merchant, UUID surveyId, UUID pollId) {
        getSurvey(actor, merchant, surveyId);
        var poll = getPoll(actor, merchant, surveyId, pollId);
        var counts = jdbc.sql("""
                SELECT o.id, o.label, COUNT(v.member_id) AS votes
                FROM survey_poll_option o
                LEFT JOIN survey_vote v ON v.merchant_id = o.merchant_id AND v.poll_id = o.poll_id
                                       AND v.option_id = o.id
                WHERE o.merchant_id = :merchant AND o.poll_id = :poll
                GROUP BY o.id, o.label, o.position
                ORDER BY o.position
                """).param("merchant", merchant).param("poll", pollId)
                .query((rs, row) -> new OptionStat(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getLong("votes")))
                .list();
        long total = counts.stream().mapToLong(OptionStat::votes).sum();
        return new PollStatsView(poll.id(), poll.kind(), counts, total);
    }

    public SurveyView getSurvey(UUID actor, UUID merchant, UUID surveyId) {
        SurveyView survey = peekSurvey(merchant, surveyId);
        tenant.requireGroupMember(actor, merchant, survey.groupId());
        return survey;
    }

    public PollView getPoll(UUID actor, UUID merchant, UUID surveyId, UUID pollId) {
        getSurvey(actor, merchant, surveyId);
        var poll = jdbc.sql("""
                SELECT id, revision_id, kind, question FROM survey_poll
                WHERE merchant_id = :merchant AND survey_id = :survey AND id = :poll
                """).param("merchant", merchant).param("survey", surveyId).param("poll", pollId)
                .query((rs, row) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("question")
                }).optional().orElseThrow(ApiFailure::notFound);
        var options = jdbc.sql("""
                SELECT id, label, position FROM survey_poll_option
                WHERE merchant_id = :merchant AND poll_id = :poll ORDER BY position
                """).param("merchant", merchant).param("poll", pollId)
                .query((rs, row) -> new OptionView(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getInt("position")))
                .list();
        return new PollView((UUID) poll[0], (UUID) poll[1], (String) poll[2], (String) poll[3], options);
    }

    @Transactional
    public void advanceDueSurveys(UUID merchant, UUID groupId) {
        var due = jdbc.sql("""
                SELECT id FROM survey_session
                WHERE merchant_id = :merchant AND group_id = :group
                  AND (
                    (status = 'ACTIVE' AND clock_timestamp() >= intake_until)
                    OR (status = 'CLOSING' AND clock_timestamp() >= closing_deadline_at)
                  )
                FOR UPDATE
                """).param("merchant", merchant).param("group", groupId).query(UUID.class).list();
        for (UUID surveyId : due) {
            jdbc.sql("""
                    UPDATE survey_session
                    SET status = CASE
                            WHEN status = 'ACTIVE' THEN 'CLOSING'
                            ELSE 'CLOSED'
                        END,
                        closing_started_at = COALESCE(closing_started_at, clock_timestamp()),
                        closed_at = CASE
                            WHEN status = 'CLOSING' OR clock_timestamp() >= closing_deadline_at
                                THEN COALESCE(closed_at, clock_timestamp())
                            ELSE closed_at
                        END,
                        evidence_watermark = COALESCE(evidence_watermark, evidence_seq),
                        version = version + 1,
                        updated_at = clock_timestamp()
                    WHERE merchant_id = :merchant AND id = :id
                    """).param("merchant", merchant).param("id", surveyId).update();
            String status = jdbc.sql("SELECT status FROM survey_session WHERE merchant_id = :m AND id = :id")
                    .param("m", merchant).param("id", surveyId).query(String.class).single();
            if ("CLOSING".equals(status) || "CLOSED".equals(status)) {
                revokeSubscriptions(merchant, surveyId, "CLOSED".equals(status) ? "EXPIRED" : "REVOKED");
            }
            maybeClose(merchant, surveyId);
        }
    }

    private void maybeClose(UUID merchant, UUID surveyId) {
        jdbc.sql("""
                UPDATE survey_session
                SET status = 'CLOSED',
                    closed_at = COALESCE(closed_at, clock_timestamp()),
                    version = version + 1,
                    updated_at = clock_timestamp()
                WHERE merchant_id = :merchant AND id = :id AND status = 'CLOSING'
                  AND clock_timestamp() >= closing_deadline_at
                """).param("merchant", merchant).param("id", surveyId).update();
    }

    private void requireActiveIntake(UUID merchant, UUID surveyId) {
        boolean ok = jdbc.sql("""
                SELECT 1 FROM survey_session
                WHERE merchant_id = :merchant AND id = :id AND status = 'ACTIVE'
                  AND starts_at <= clock_timestamp() AND clock_timestamp() < intake_until
                """).param("merchant", merchant).param("id", surveyId).query(Integer.class).optional().isPresent();
        if (!ok) {
            throw ApiFailure.conflict("SURVEY_WINDOW_CLOSED", "Survey intake window is closed");
        }
    }

    private void revokeSubscriptions(UUID merchant, UUID surveyId, String status) {
        jdbc.sql("""
                UPDATE subscription
                SET status = :status, updated_at = clock_timestamp()
                WHERE merchant_id = :merchant AND survey_id = :survey AND status = 'ACTIVE'
                """).param("merchant", merchant).param("survey", surveyId).param("status", status).update();
    }

    private SurveyView peekSurvey(UUID merchant, UUID surveyId) {
        return jdbc.sql("""
                SELECT id, group_id, status, starts_at, planned_ends_at, intake_until, closing_deadline_at,
                       closing_started_at, closed_at, evidence_watermark, version
                FROM survey_session WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", surveyId)
                .query(this::surveyRow).optional().orElseThrow(ApiFailure::notFound);
    }

    private SurveyView surveyRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SurveyView(
                rs.getObject("id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getString("status"),
                ts(rs, "starts_at"),
                ts(rs, "planned_ends_at"),
                ts(rs, "intake_until"),
                ts(rs, "closing_deadline_at"),
                ts(rs, "closing_started_at"),
                ts(rs, "closed_at"),
                rs.getObject("evidence_watermark") == null ? null : rs.getLong("evidence_watermark"),
                rs.getLong("version"));
    }

    private static java.time.Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        var value = rs.getObject(col, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private void storeCommand(UUID merchant, UUID actor, UUID surveyId, UUID key, String hash, SurveyView view) {
        try {
            jdbc.sql("""
                    INSERT INTO api_command (merchant_id, actor_id, resource_type, resource_id, command_key,
                                             request_hash, response_json)
                    VALUES (:merchant, :actor, 'survey_close', :survey, :key, :hash, CAST(:json AS jsonb))
                    """).param("merchant", merchant).param("actor", actor).param("survey", surveyId)
                    .param("key", key).param("hash", hash)
                    .param("json", mapper.writeValueAsString(view)).update();
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
