package com.craftpilot.runtime.memory;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceDtos.EvidenceVerifyRequest;
import com.craftpilot.evidence.EvidenceService;
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
public class MemoryService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TenantAccess tenant;
    private final EvidenceService evidence;

    public MemoryService(JdbcClient jdbc, ObjectMapper mapper, TenantAccess tenant, EvidenceService evidence) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tenant = tenant;
        this.evidence = evidence;
    }

    public record ProposalView(UUID id, String status, String scopeType, String scopeKey, String summary) {}
    public record EntryView(UUID id, String status, long version, String summary) {}

    @Transactional
    public ProposalView propose(UUID actor, UUID merchant, UUID surveyId, UUID runId, String scopeType,
                                String scopeKey, String summary, List<UUID> evidenceIds) {
        tenant.requireOwner(actor, merchant);
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw ApiFailure.invalid("Memory requires evidence");
        }
        if (surveyId != null) {
            var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(evidenceIds));
            if (!verified.invalidIds().isEmpty() || verified.validIds().isEmpty()) {
                throw ApiFailure.conflict("EVIDENCE_INVALID", "Memory evidence failed verification");
            }
        }
        UUID id = UUID.randomUUID();
        String hash = sha(scopeType + "|" + scopeKey + "|" + summary + "|" + evidenceIds);
        jdbc.sql("""
                INSERT INTO memory_proposal
                (merchant_id, id, survey_id, run_id, scope_type, scope_key, summary, content_hash,
                 evidence_ids, status, created_by)
                VALUES (:m, :id, :survey, :run, :stype, :skey, :summary, :hash, CAST(:ev AS jsonb),
                        'PROPOSED', :actor)
                """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                .param("stype", scopeType).param("skey", scopeKey).param("summary", summary)
                .param("hash", hash).param("ev", write(evidenceIds)).param("actor", actor).update();
        return new ProposalView(id, "PROPOSED", scopeType, scopeKey, summary);
    }

    @Transactional
    public EntryView publish(UUID actor, UUID merchant, UUID proposalId, UUID approvalId) {
        tenant.requireOwner(actor, merchant);
        var proposal = jdbc.sql("""
                SELECT id, survey_id, scope_type, scope_key, summary, content_hash, evidence_ids::text AS ev,
                       status
                FROM memory_proposal WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", proposalId)
                .query((rs, i) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "surveyId", rs.getObject("survey_id", UUID.class),
                        "scopeType", rs.getString("scope_type"),
                        "scopeKey", rs.getString("scope_key"),
                        "summary", rs.getString("summary"),
                        "hash", rs.getString("content_hash"),
                        "ev", rs.getString("ev"),
                        "status", rs.getString("status")))
                .optional().orElseThrow(ApiFailure::notFound);
        if (!List.of("PROPOSED", "APPROVED").contains(String.valueOf(proposal.get("status")))) {
            throw ApiFailure.conflict("PROPOSAL_NOT_PUBLISHABLE", "Proposal cannot be published");
        }
        if (approvalId != null) {
            String approvalStatus = jdbc.sql("""
                    SELECT status FROM approval WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", approvalId).query(String.class)
                    .optional().orElseThrow(ApiFailure::notFound);
            if (!List.of("APPROVED", "CONSUMED").contains(approvalStatus)) {
                throw ApiFailure.conflict("APPROVAL_REQUIRED", "Memory publish requires approval");
            }
        }
        UUID surveyId = (UUID) proposal.get("surveyId");
        List<UUID> evidenceIds = readUuidList(String.valueOf(proposal.get("ev")));
        if (surveyId != null) {
            var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(evidenceIds));
            if (!verified.invalidIds().isEmpty()) {
                throw ApiFailure.conflict("EVIDENCE_INVALID", "Evidence no longer valid for memory");
            }
        }
        UUID entryId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO memory_entry
                (merchant_id, id, proposal_id, scope_type, scope_key, summary, content_hash, evidence_ids,
                 version, status, published_by)
                VALUES (:m, :id, :proposal, :stype, :skey, :summary, :hash, CAST(:ev AS jsonb),
                        1, 'ACTIVE', :actor)
                """).param("m", merchant).param("id", entryId).param("proposal", proposalId)
                .param("stype", proposal.get("scopeType")).param("skey", proposal.get("scopeKey"))
                .param("summary", proposal.get("summary")).param("hash", proposal.get("hash"))
                .param("ev", write(evidenceIds)).param("actor", actor).update();
        jdbc.sql("""
                UPDATE memory_proposal SET status = 'PUBLISHED', approval_id = :approval
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", proposalId).param("approval", approvalId).update();
        return new EntryView(entryId, "ACTIVE", 1, String.valueOf(proposal.get("summary")));
    }

    @Transactional
    public EntryView revoke(UUID actor, UUID merchant, UUID entryId, long expectedVersion) {
        tenant.requireOwner(actor, merchant);
        int updated = jdbc.sql("""
                UPDATE memory_entry SET status = 'REVOKED', revoked_at = clock_timestamp(), revoked_by = :actor,
                       version = version + 1
                WHERE merchant_id = :m AND id = :id AND status = 'ACTIVE' AND version = :v
                """).param("m", merchant).param("id", entryId).param("actor", actor)
                .param("v", expectedVersion).update();
        if (updated == 0) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Memory revoke version mismatch");
        }
        return jdbc.sql("""
                SELECT id, status, version, summary FROM memory_entry WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", entryId)
                .query((rs, i) -> new EntryView(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version"), rs.getString("summary")))
                .single();
    }

    public List<EntryView> searchActive(UUID actor, UUID merchant, String scopeType, String scopeKey) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT id, status, version, summary FROM memory_entry
                WHERE merchant_id = :m AND scope_type = :stype AND scope_key = :skey AND status = 'ACTIVE'
                ORDER BY published_at DESC
                """).param("m", merchant).param("stype", scopeType).param("skey", scopeKey)
                .query((rs, i) -> new EntryView(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version"), rs.getString("summary")))
                .list();
    }

    private List<UUID> readUuidList(String json) {
        try {
            @SuppressWarnings("unchecked")
            List<String> raw = mapper.readValue(json == null ? "[]" : json, List.class);
            return raw.stream().map(UUID::fromString).toList();
        } catch (Exception ex) {
            return List.of();
        }
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
