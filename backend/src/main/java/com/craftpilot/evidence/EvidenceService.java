package com.craftpilot.evidence;

import static com.craftpilot.evidence.EvidenceDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {
    private static final Pattern MATERIAL = Pattern.compile("\\b([A-Z]{2,}-\\d{1,5})\\b");

    private final JdbcClient jdbc;
    private final TenantAccess tenant;

    public EvidenceService(JdbcClient jdbc, TenantAccess tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public UUID createFromOpinion(UUID merchant, UUID surveyId, UUID revisionId, UUID opinionId, String summary) {
        UUID existing = jdbc.sql("""
                SELECT id FROM evidence_ref
                WHERE merchant_id = :m AND survey_id = :survey AND source_type = 'FEEDBACK_OPINION'
                  AND source_id = :source
                """).param("m", merchant).param("survey", surveyId).param("source", opinionId)
                .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return existing;
        }
        long seq = jdbc.sql("""
                UPDATE survey_session SET evidence_seq = evidence_seq + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :survey
                RETURNING evidence_seq
                """).param("m", merchant).param("survey", surveyId).query(Long.class).single();
        UUID evidenceId = UUID.randomUUID();
        String checksum = sha(summary == null ? "" : summary);
        jdbc.sql("""
                INSERT INTO evidence_ref
                (merchant_id, id, survey_id, revision_id, source_type, source_id, captured_at, grade,
                 checksum, visibility, seq, summary)
                VALUES (:m, :id, :survey, :revision, 'FEEDBACK_OPINION', :source, clock_timestamp(), 'A',
                        :checksum, 'ACTIVE', :seq, :summary)
                """).param("m", merchant).param("id", evidenceId).param("survey", surveyId)
                .param("revision", revisionId).param("source", opinionId).param("checksum", checksum)
                .param("seq", seq).param("summary", summary == null ? "" : summary).update();

        String norm = normalize(summary);
        String material = extractMaterial(summary);
        jdbc.sql("""
                INSERT INTO evidence_chunk
                (merchant_id, id, evidence_id, survey_id, revision_id, chunk_ordinal, doc_version,
                 content_text, content_norm, material_code, visibility)
                VALUES (:m, :id, :evidence, :survey, :revision, 0, 'r2', :text, :norm, :material, 'ACTIVE')
                """).param("m", merchant).param("id", UUID.randomUUID()).param("evidence", evidenceId)
                .param("survey", surveyId).param("revision", revisionId)
                .param("text", summary == null ? "" : summary).param("norm", norm).param("material", material)
                .update();
        return evidenceId;
    }

    public EvidenceView get(UUID actor, UUID merchant, UUID evidenceId) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT e.id, e.survey_id, e.revision_id, e.source_type, e.source_id, e.grade, e.visibility,
                       e.seq, e.summary, e.checksum, e.captured_at, s.group_id
                FROM evidence_ref e
                JOIN survey_session s ON s.merchant_id = e.merchant_id AND s.id = e.survey_id
                WHERE e.merchant_id = :m AND e.id = :id
                """).param("m", merchant).param("id", evidenceId)
                .query((rs, row) -> {
                    tenant.requireGroupMember(actor, merchant, rs.getObject("group_id", UUID.class));
                    return new EvidenceView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("survey_id", UUID.class),
                            rs.getObject("revision_id", UUID.class),
                            rs.getString("source_type"),
                            rs.getObject("source_id", UUID.class),
                            rs.getString("grade"),
                            rs.getString("visibility"),
                            rs.getLong("seq"),
                            rs.getString("summary"),
                            rs.getString("checksum"),
                            rs.getObject("captured_at", java.time.OffsetDateTime.class).toInstant());
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    public EvidenceVerifyResult verify(UUID actor, UUID merchant, UUID surveyId, EvidenceVerifyRequest request) {
        UUID groupId = jdbc.sql("SELECT group_id FROM survey_session WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", surveyId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupMember(actor, merchant, groupId);
        List<UUID> valid = new ArrayList<>();
        List<UUID> invalid = new ArrayList<>();
        for (UUID evidenceId : request.evidenceIds()) {
            boolean ok = jdbc.sql("""
                    SELECT 1 FROM evidence_ref
                    WHERE merchant_id = :m AND id = :id AND survey_id = :survey AND visibility = 'ACTIVE'
                    """).param("m", merchant).param("id", evidenceId).param("survey", surveyId)
                    .query(Integer.class).optional().isPresent();
            if (ok) {
                valid.add(evidenceId);
            } else {
                invalid.add(evidenceId);
            }
        }
        return new EvidenceVerifyResult(List.copyOf(valid), List.copyOf(invalid));
    }

    @Transactional
    public void revokeBySource(UUID merchant, UUID surveyId, String sourceType, UUID sourceId) {
        jdbc.sql("""
                UPDATE evidence_ref SET visibility = 'REVOKED'
                WHERE merchant_id = :m AND survey_id = :survey AND source_type = :type AND source_id = :source
                """).param("m", merchant).param("survey", surveyId).param("type", sourceType)
                .param("source", sourceId).update();
        jdbc.sql("""
                UPDATE evidence_chunk c
                SET visibility = 'REVOKED'
                FROM evidence_ref e
                WHERE c.merchant_id = e.merchant_id AND c.evidence_id = e.id
                  AND e.merchant_id = :m AND e.survey_id = :survey AND e.source_type = :type
                  AND e.source_id = :source
                """).param("m", merchant).param("survey", surveyId).param("type", sourceType)
                .param("source", sourceId).update();
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static String extractMaterial(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = MATERIAL.matcher(text.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
