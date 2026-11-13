package com.craftpilot.feedback;

import static com.craftpilot.feedback.FeedbackDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.events.OutboxWriter;
import com.craftpilot.tenant.TenantAccess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final OpinionExtractor extractor;
    private final EvidenceService evidence;
    private final OutboxWriter outbox;

    public FeedbackService(JdbcClient jdbc, TenantAccess tenant, OpinionExtractor extractor,
                           EvidenceService evidence, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.extractor = extractor;
        this.evidence = evidence;
        this.outbox = outbox;
    }

    @Transactional
    public AttributionView processMessage(UUID actor, UUID merchant, UUID messageId) {
        tenant.requireMember(actor, merchant);
        var message = jdbc.sql("""
                SELECT id, group_id, sender_id, text_body, reply_to_id, card_revision_id, survey_id, received_at
                FROM group_message WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", messageId)
                .query((rs, row) -> {
                    Map<String, Object> rowMap = new HashMap<>();
                    rowMap.put("id", rs.getObject("id", UUID.class));
                    rowMap.put("groupId", rs.getObject("group_id", UUID.class));
                    rowMap.put("senderId", rs.getObject("sender_id", UUID.class));
                    rowMap.put("text", rs.getString("text_body") == null ? "" : rs.getString("text_body"));
                    rowMap.put("replyTo", rs.getObject("reply_to_id", UUID.class));
                    rowMap.put("card", rs.getObject("card_revision_id", UUID.class));
                    rowMap.put("surveyId", rs.getObject("survey_id", UUID.class));
                    rowMap.put("receivedAt", rs.getObject("received_at", java.time.OffsetDateTime.class));
                    return rowMap;
                })
                .optional().orElseThrow(ApiFailure::notFound);

        UUID surveyId = (UUID) message.get("surveyId");
        if (surveyId == null) {
            throw ApiFailure.conflict("MESSAGE_NOT_IN_SURVEY", "Message is not linked to a survey");
        }
        tenant.requireGroupMember(actor, merchant, (UUID) message.get("groupId"));

        var existing = jdbc.sql("""
                SELECT id FROM message_attribution
                WHERE merchant_id = :m AND message_id = :message AND survey_id = :survey
                """).param("m", merchant).param("message", messageId).param("survey", surveyId)
                .query(UUID.class).optional();
        if (existing.isPresent()) {
            return getAttribution(actor, merchant, existing.get());
        }

        jdbc.sql("SELECT id FROM survey_session WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", surveyId).query(UUID.class).single();

        boolean inWindow = jdbc.sql("""
                SELECT 1 FROM survey_session
                WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE'
                  AND starts_at <= :received AND :received < intake_until
                """).param("m", merchant).param("id", surveyId)
                .param("received", message.get("receivedAt")).query(Integer.class).optional().isPresent();

        UUID card = (UUID) message.get("card");
        UUID revision = null;
        String status;
        String reason;
        if (!inWindow) {
            status = "OUT_OF_WINDOW";
            reason = "OUTSIDE_INTAKE_WINDOW";
        } else if (card != null) {
            boolean allowed = jdbc.sql("""
                    SELECT 1 FROM survey_revision
                    WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :revision
                    """).param("m", merchant).param("survey", surveyId).param("revision", card)
                    .query(Integer.class).optional().isPresent();
            if (allowed) {
                revision = card;
                status = "ATTRIBUTED";
                reason = "CARD_REVISION";
            } else {
                status = "UNATTRIBUTED";
                reason = "CARD_NOT_IN_SURVEY_WHITELIST";
            }
        } else if (message.get("replyTo") != null) {
            var parent = jdbc.sql("""
                    SELECT revision_id, status FROM message_attribution
                    WHERE merchant_id = :m AND message_id = :message AND survey_id = :survey
                    """).param("m", merchant).param("message", message.get("replyTo")).param("survey", surveyId)
                    .query((rs, row) -> Map.entry(rs.getObject("revision_id", UUID.class), rs.getString("status")))
                    .optional();
            if (parent.isPresent() && "ATTRIBUTED".equals(parent.get().getValue()) && parent.get().getKey() != null) {
                revision = parent.get().getKey();
                status = "ATTRIBUTED";
                reason = "REPLY_CHAIN";
            } else {
                status = "UNATTRIBUTED";
                reason = "REPLY_WITHOUT_ATTRIBUTED_PARENT";
            }
        } else {
            long whitelist = jdbc.sql("""
                    SELECT COUNT(*) FROM survey_revision WHERE merchant_id = :m AND survey_id = :survey
                    """).param("m", merchant).param("survey", surveyId).query(Long.class).single();
            if (whitelist > 1) {
                status = "AMBIGUOUS";
                reason = "MULTI_REVISION_NO_CARD";
            } else {
                status = "UNATTRIBUTED";
                reason = "NO_EXPLICIT_REVISION_ANCHOR";
            }
        }

        UUID attributionId = UUID.randomUUID();
        String quote = ((String) message.get("text"));
        jdbc.sql("""
                INSERT INTO message_attribution
                (merchant_id, id, message_id, survey_id, revision_id, status, reason_code, source_quote)
                VALUES (:m, :id, :message, :survey, :revision, :status, :reason, :quote)
                """).param("m", merchant).param("id", attributionId).param("message", messageId)
                .param("survey", surveyId).param("revision", revision).param("status", status)
                .param("reason", reason).param("quote", quote.isBlank() ? null : quote).update();

        if ("ATTRIBUTED".equals(status) && revision != null) {
            extractAndAggregate(merchant, surveyId, messageId, attributionId, revision,
                    (UUID) message.get("senderId"), quote);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("merchantId", merchant);
        payload.put("surveyId", surveyId);
        payload.put("messageId", messageId);
        payload.put("status", status);
        payload.put("revisionId", revision == null ? "" : revision.toString());
        outbox.enqueue(merchant, "message_attribution", attributionId, "FEEDBACK_ATTRIBUTED", payload);
        return getAttribution(actor, merchant, attributionId);
    }

    @Transactional
    public AttributionView manualAttribute(UUID actor, UUID merchant, UUID surveyId, UUID attributionId,
                                           ManualAttributionRequest request) {
        var current = getAttribution(actor, merchant, attributionId);
        tenant.requireGroupAdminOrOwner(actor, merchant, groupOfSurvey(merchant, surveyId));
        if (!surveyId.equals(current.surveyId())) {
            throw ApiFailure.notFound();
        }
        boolean active = jdbc.sql("""
                SELECT 1 FROM survey_session
                WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE'
                  AND starts_at <= clock_timestamp() AND clock_timestamp() < intake_until
                """).param("m", merchant).param("id", surveyId).query(Integer.class).optional().isPresent();
        if (!active) {
            throw ApiFailure.conflict("SURVEY_WINDOW_CLOSED", "Survey intake window is closed");
        }
        jdbc.sql("""
                SELECT 1 FROM survey_revision
                WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :revision
                """).param("m", merchant).param("survey", surveyId).param("revision", request.revisionId())
                .query(Integer.class).optional().orElseThrow(ApiFailure::notFound);
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Attribution version mismatch");
        }

        jdbc.sql("""
                UPDATE message_attribution
                SET revision_id = :revision, status = 'ATTRIBUTED', reason_code = 'MANUAL_OVERRIDE',
                    version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", attributionId).param("revision", request.revisionId())
                .update();
        jdbc.sql("""
                INSERT INTO feedback_attribution_event
                (merchant_id, id, feedback_id, survey_id, old_revision_id, new_revision_id, actor_id, version, event_key)
                VALUES (:m, :id, :feedback, :survey, :old, :new, :actor, :version, :key)
                """).param("m", merchant).param("id", UUID.randomUUID()).param("feedback", attributionId)
                .param("survey", surveyId).param("old", current.revisionId())
                .param("new", request.revisionId()).param("actor", actor)
                .param("version", current.version() + 1).param("key", UUID.randomUUID()).update();

        var message = jdbc.sql("""
                SELECT message_id, source_quote FROM message_attribution WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", attributionId)
                .query((rs, row) -> Map.entry(rs.getObject("message_id", UUID.class), rs.getString("source_quote")))
                .single();
        UUID author = jdbc.sql("SELECT sender_id FROM group_message WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", message.getKey()).query(UUID.class).single();
        // Supersede prior opinions/evidence for this message before re-aggregating onto the override revision.
        var priorOpinions = jdbc.sql("""
                SELECT id FROM feedback_opinion
                WHERE merchant_id = :m AND message_id = :message AND status = 'ACTIVE'
                """).param("m", merchant).param("message", message.getKey()).query(UUID.class).list();
        for (UUID opinionId : priorOpinions) {
            evidence.revokeBySource(merchant, surveyId, "FEEDBACK_OPINION", opinionId);
        }
        jdbc.sql("""
                UPDATE feedback_opinion SET status = 'SUPERSEDED'
                WHERE merchant_id = :m AND message_id = :message AND status = 'ACTIVE'
                """).param("m", merchant).param("message", message.getKey()).update();
        extractAndAggregate(merchant, surveyId, message.getKey(), attributionId, request.revisionId(),
                author, message.getValue() == null ? "" : message.getValue());
        return getAttribution(actor, merchant, attributionId);
    }

    public AttributionView getAttribution(UUID actor, UUID merchant, UUID attributionId) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT a.id, a.message_id, a.survey_id, a.revision_id, a.status, a.reason_code, a.source_quote, a.version,
                       s.group_id
                FROM message_attribution a
                JOIN survey_session s ON s.merchant_id = a.merchant_id AND s.id = a.survey_id
                WHERE a.merchant_id = :m AND a.id = :id
                """).param("m", merchant).param("id", attributionId)
                .query((rs, row) -> {
                    tenant.requireGroupMember(actor, merchant, rs.getObject("group_id", UUID.class));
                    return new AttributionView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("message_id", UUID.class),
                            rs.getObject("survey_id", UUID.class),
                            rs.getObject("revision_id", UUID.class),
                            rs.getString("status"),
                            rs.getString("reason_code"),
                            rs.getString("source_quote"),
                            rs.getLong("version"));
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    public List<IssueView> listIssues(UUID actor, UUID merchant, UUID surveyId, UUID revisionId) {
        UUID groupId = groupOfSurvey(merchant, surveyId);
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
        var issues = jdbc.sql("""
                SELECT id, revision_id, issue_type, polarity, title, independent_authors, status
                FROM feedback_issue
                WHERE merchant_id = :m AND survey_id = :survey
                  AND (:revision IS NULL OR revision_id = :revision)
                ORDER BY independent_authors DESC, id
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query((rs, row) -> new IssueView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getString("issue_type"),
                        rs.getString("polarity"),
                        rs.getString("title"),
                        rs.getInt("independent_authors"),
                        rs.getString("status"),
                        List.of()))
                .list();
        return issues.stream().map(issue -> {
            var opinions = jdbc.sql("""
                    SELECT o.id, o.message_id, o.revision_id, o.author_id, o.polarity, o.attribute_key,
                           o.canonical_text, o.quote_text, o.confidence
                    FROM issue_opinion io
                    JOIN feedback_opinion o ON o.merchant_id = io.merchant_id AND o.id = io.opinion_id
                    WHERE io.merchant_id = :m AND io.issue_id = :issue AND o.status = 'ACTIVE'
                    ORDER BY o.created_at, o.id
                    """).param("m", merchant).param("issue", issue.id())
                    .query((rs, row) -> new OpinionView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("message_id", UUID.class),
                            rs.getObject("revision_id", UUID.class),
                            rs.getObject("author_id", UUID.class),
                            rs.getString("polarity"),
                            rs.getString("attribute_key"),
                            rs.getString("canonical_text"),
                            rs.getString("quote_text"),
                            rs.getBigDecimal("confidence")))
                    .list();
            return new IssueView(issue.id(), issue.revisionId(), issue.issueType(), issue.polarity(),
                    issue.title(), issue.independentAuthors(), issue.status(), opinions);
        }).toList();
    }

    public FeedbackStatsView stats(UUID actor, UUID merchant, UUID surveyId, UUID revisionId) {
        tenant.requireGroupAdminOrOwner(actor, merchant, groupOfSurvey(merchant, surveyId));
        long attributed = jdbc.sql("""
                SELECT COUNT(*) FROM message_attribution
                WHERE merchant_id = :m AND survey_id = :survey AND status = 'ATTRIBUTED'
                  AND (:revision IS NULL OR revision_id = :revision)
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Long.class).single();
        long opinions = jdbc.sql("""
                SELECT COUNT(*) FROM feedback_opinion
                WHERE merchant_id = :m AND survey_id = :survey AND status = 'ACTIVE'
                  AND (:revision IS NULL OR revision_id = :revision)
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Long.class).single();
        long issues = jdbc.sql("""
                SELECT COUNT(*) FROM feedback_issue
                WHERE merchant_id = :m AND survey_id = :survey
                  AND (:revision IS NULL OR revision_id = :revision)
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Long.class).single();
        long authors = jdbc.sql("""
                SELECT COUNT(DISTINCT author_id) FROM feedback_opinion
                WHERE merchant_id = :m AND survey_id = :survey AND status = 'ACTIVE'
                  AND (:revision IS NULL OR revision_id = :revision)
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Long.class).single();
        return new FeedbackStatsView(surveyId, revisionId, attributed, opinions, issues, authors);
    }

    private void extractAndAggregate(UUID merchant, UUID surveyId, UUID messageId, UUID attributionId,
                                     UUID revisionId, UUID authorId, String text) {
        UUID styleId = jdbc.sql("""
                SELECT style_id FROM sample_revision WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", revisionId).query(UUID.class).single();
        for (var extracted : extractor.extract(text)) {
            UUID opinionId = UUID.randomUUID();
            UUID persistedOpinionId = jdbc.sql("""
                    INSERT INTO feedback_opinion
                    (merchant_id, id, survey_id, message_id, attribution_id, revision_id, author_id,
                     polarity, attribute_key, canonical_text, quote_text, confidence, extractor_version)
                    VALUES (:m, :id, :survey, :message, :attr, :revision, :author, :polarity, :attrKey,
                            :canonical, :quote, :confidence, 'r2-rule-1')
                    ON CONFLICT (merchant_id, message_id, attribute_key, polarity, canonical_text)
                    DO UPDATE SET attribution_id = EXCLUDED.attribution_id,
                                  revision_id = EXCLUDED.revision_id,
                                  quote_text = EXCLUDED.quote_text,
                                  confidence = EXCLUDED.confidence,
                                  status = 'ACTIVE'
                    RETURNING id
                    """).param("m", merchant).param("id", opinionId).param("survey", surveyId)
                    .param("message", messageId).param("attr", attributionId).param("revision", revisionId)
                    .param("author", authorId).param("polarity", extracted.polarity())
                    .param("attrKey", extracted.attributeKey()).param("canonical", extracted.canonicalText())
                    .param("quote", extracted.quoteText()).param("confidence", extracted.confidence())
                    .query(UUID.class).single();
            opinionId = persistedOpinionId;
            String issueType = extracted.attributeKey() + ":" + extracted.canonicalText();
            UUID issueId = jdbc.sql("""
                    INSERT INTO feedback_issue
                    (merchant_id, id, survey_id, style_id, revision_id, issue_type, polarity, title, independent_authors)
                    VALUES (:m, :id, :survey, :style, :revision, :type, :polarity, :title, 0)
                    ON CONFLICT (merchant_id, survey_id, revision_id, issue_type, polarity)
                    DO UPDATE SET updated_at = clock_timestamp()
                    RETURNING id
                    """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                    .param("style", styleId).param("revision", revisionId).param("type", issueType)
                    .param("polarity", extracted.polarity())
                    .param("title", extracted.attributeKey() + " " + extracted.polarity().toLowerCase())
                    .query(UUID.class).optional().orElseGet(() -> jdbc.sql("""
                            SELECT id FROM feedback_issue
                            WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :revision
                              AND issue_type = :type AND polarity = :polarity
                            """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                            .param("type", issueType).param("polarity", extracted.polarity())
                            .query(UUID.class).single());
            jdbc.sql("""
                    INSERT INTO issue_opinion (merchant_id, issue_id, opinion_id)
                    VALUES (:m, :issue, :opinion)
                    ON CONFLICT DO NOTHING
                    """).param("m", merchant).param("issue", issueId).param("opinion", opinionId).update();
            jdbc.sql("""
                    UPDATE feedback_issue
                    SET independent_authors = (
                        SELECT COUNT(DISTINCT o.author_id)
                        FROM issue_opinion io
                        JOIN feedback_opinion o ON o.merchant_id = io.merchant_id AND o.id = io.opinion_id
                        WHERE io.merchant_id = :m AND io.issue_id = :issue AND o.status = 'ACTIVE'
                    ), updated_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :issue
                    """).param("m", merchant).param("issue", issueId).update();

            evidence.createFromOpinion(merchant, surveyId, revisionId, opinionId, extracted.quoteText());
        }
    }

    @Transactional
    public void invalidateMessageEvidence(UUID merchant, UUID messageId) {
        var rows = jdbc.sql("""
                SELECT id, survey_id FROM message_attribution
                WHERE merchant_id = :m AND message_id = :message AND status <> 'INVALIDATED'
                """).param("m", merchant).param("message", messageId)
                .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("survey_id", UUID.class)))
                .list();
        for (var row : rows) {
            jdbc.sql("""
                    UPDATE message_attribution
                    SET status = 'INVALIDATED', reason_code = 'MESSAGE_REVOKED', updated_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", row.getKey()).update();
            jdbc.sql("""
                    UPDATE feedback_opinion SET status = 'INVALIDATED'
                    WHERE merchant_id = :m AND message_id = :message
                    """).param("m", merchant).param("message", messageId).update();
            var opinions = jdbc.sql("""
                    SELECT id FROM feedback_opinion WHERE merchant_id = :m AND message_id = :message
                    """).param("m", merchant).param("message", messageId).query(UUID.class).list();
            for (UUID opinionId : opinions) {
                evidence.revokeBySource(merchant, row.getValue(), "FEEDBACK_OPINION", opinionId);
            }
        }
    }

    private UUID groupOfSurvey(UUID merchant, UUID surveyId) {
        return jdbc.sql("SELECT group_id FROM survey_session WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", surveyId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
    }
}
