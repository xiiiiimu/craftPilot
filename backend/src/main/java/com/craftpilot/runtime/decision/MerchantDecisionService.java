package com.craftpilot.runtime.decision;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantDecisionService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TenantAccess tenant;

    public MerchantDecisionService(JdbcClient jdbc, ObjectMapper mapper, TenantAccess tenant) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tenant = tenant;
    }

    public record DecisionView(UUID id, String status, String recommendation, String reportHash, long version) {}

    @Transactional
    public UUID publishReport(UUID merchant, UUID surveyId, UUID runId, Map<String, Object> content,
                              List<UUID> evidenceIds) {
        var existing = jdbc.sql("""
                SELECT id FROM research_report WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", runId).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String body = write(content);
        String hash = sha(body);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO research_report
                (merchant_id, id, survey_id, run_id, content_hash, content_json, evidence_ids)
                VALUES (:m, :id, :survey, :run, :hash, CAST(:body AS jsonb), CAST(:ev AS jsonb))
                """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                .param("hash", hash).param("body", body).param("ev", write(evidenceIds)).update();
        return id;
    }

    @Transactional
    public DecisionView requestDecision(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID reportId,
                                        UUID revisionId, String recommendation) {
        tenant.requireOwner(actor, merchant);
        String reportHash = jdbc.sql("""
                SELECT content_hash FROM research_report WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", reportId).query(String.class)
                .optional().orElseThrow(ApiFailure::notFound);
        var existing = jdbc.sql("""
                SELECT id, status, recommendation, report_hash, version FROM merchant_decision
                WHERE merchant_id = :m AND report_id = :report AND revision_id = :rev
                """).param("m", merchant).param("report", reportId).param("rev", revisionId)
                .query((rs, i) -> new DecisionView(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("recommendation"),
                        rs.getString("report_hash"), rs.getLong("version")))
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO merchant_decision
                (merchant_id, id, survey_id, run_id, report_id, revision_id, recommendation, status, report_hash)
                VALUES (:m, :id, :survey, :run, :report, :rev, :rec, 'PENDING', :hash)
                """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                .param("report", reportId).param("rev", revisionId).param("rec", recommendation)
                .param("hash", reportHash).update();
        return new DecisionView(id, "PENDING", recommendation, reportHash, 0);
    }

    @Transactional
    public DecisionView decide(UUID actor, UUID merchant, UUID decisionId, String choice, String note,
                               long expectedVersion) {
        tenant.requireOwner(actor, merchant);
        if (!List.of("CONFIRMED", "REJECTED", "REVISION_REQUESTED").contains(choice)) {
            throw ApiFailure.invalid("Invalid decision choice");
        }
        int updated = jdbc.sql("""
                UPDATE merchant_decision
                SET status = :choice, decided_by = :actor, decision_choice = :choice, decision_note = :note,
                    decided_at = clock_timestamp(), version = version + 1
                WHERE merchant_id = :m AND id = :id AND status = 'PENDING' AND version = :v
                """).param("m", merchant).param("id", decisionId).param("choice", choice)
                .param("actor", actor).param("note", note == null ? "" : note)
                .param("v", expectedVersion).update();
        if (updated == 0) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Decision version mismatch");
        }
        return jdbc.sql("""
                SELECT id, status, recommendation, report_hash, version FROM merchant_decision
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", decisionId)
                .query((rs, i) -> new DecisionView(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("recommendation"),
                        rs.getString("report_hash"), rs.getLong("version")))
                .single();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
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
