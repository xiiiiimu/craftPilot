package com.craftpilot.runtime.skill;

import com.craftpilot.common.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SkillRegistry {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SkillRegistry(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record SkillView(String id, String version, String roleCode, String instructions,
                            List<String> toolWhitelist, String contentHash, String status) {}

    public SkillView requirePublished(String skillId, String version) {
        return jdbc.sql("""
                SELECT id, version, role_code, instructions, tool_whitelist::text AS tools, content_hash, status
                FROM skill_release WHERE id = :id AND version = :ver
                """).param("id", skillId).param("ver", version)
                .query((rs, i) -> new SkillView(
                        rs.getString("id"), rs.getString("version"), rs.getString("role_code"),
                        rs.getString("instructions"), readList(rs.getString("tools")),
                        rs.getString("content_hash"), rs.getString("status")))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    @Transactional
    public UUID snapshotForRun(UUID merchant, UUID surveyId, UUID runId, String workflowId,
                               String workflowVersion, List<Map<String, String>> skills) {
        var existing = jdbc.sql("""
                SELECT id FROM skill_snapshot WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", runId).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Map<String, String> ref : skills) {
            SkillView skill = requirePublished(ref.get("id"), ref.get("version"));
            if (!"PUBLISHED".equals(skill.status())) {
                throw ApiFailure.conflict("SKILL_REVOKED", "Skill is revoked: " + skill.id());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skillId", skill.id());
            entry.put("version", skill.version());
            entry.put("roleCode", skill.roleCode());
            entry.put("contentHash", skill.contentHash());
            entry.put("toolWhitelist", skill.toolWhitelist());
            entry.put("instructions", skill.instructions());
            manifest.add(entry);
        }
        String json = write(manifest);
        String hash = sha(workflowId + "|" + workflowVersion + "|" + json);
        UUID snapshotId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO skill_snapshot
                (merchant_id, id, survey_id, run_id, workflow_id, workflow_version, manifest_json, content_hash)
                VALUES (:m, :id, :survey, :run, :wf, :ver, CAST(:manifest AS jsonb), :hash)
                """).param("m", merchant).param("id", snapshotId).param("survey", surveyId).param("run", runId)
                .param("wf", workflowId).param("ver", workflowVersion).param("manifest", json).param("hash", hash)
                .update();
        for (Map<String, Object> entry : manifest) {
            jdbc.sql("""
                    INSERT INTO agent_run_skill
                    (merchant_id, run_id, skill_id, skill_version, snapshot_id, role_code)
                    VALUES (:m, :run, :sid, :sver, :snap, :role)
                    """).param("m", merchant).param("run", runId).param("sid", entry.get("skillId"))
                    .param("sver", entry.get("version")).param("snap", snapshotId)
                    .param("role", entry.get("roleCode")).update();
        }
        return snapshotId;
    }

    public Map<String, Object> activate(UUID merchant, UUID runId, String roleCode) {
        var snap = jdbc.sql("""
                SELECT id, content_hash, manifest_json::text AS body
                FROM skill_snapshot WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "hash", rs.getString("content_hash"),
                        "body", rs.getString("body")))
                .optional().orElseThrow(() -> ApiFailure.conflict("SKILL_SNAPSHOT_MISSING", "No skill snapshot"));
        List<Map<String, Object>> manifest = readManifest(String.valueOf(snap.get("body")));
        Map<String, Object> chosen = manifest.stream()
                .filter(e -> roleCode.equals(String.valueOf(e.get("roleCode"))))
                .findFirst()
                .orElseThrow(() -> ApiFailure.conflict("SKILL_ROLE_MISSING", "Role not in snapshot"));
        String skillId = String.valueOf(chosen.get("skillId"));
        String version = String.valueOf(chosen.get("version"));
        var live = jdbc.sql("""
                SELECT status, content_hash FROM skill_release WHERE id = :id AND version = :ver
                """).param("id", skillId).param("ver", version)
                .query((rs, i) -> Map.of("status", rs.getString("status"), "hash", rs.getString("content_hash")))
                .optional().orElseThrow(ApiFailure::notFound);
        if (!"PUBLISHED".equals(live.get("status"))) {
            throw ApiFailure.conflict("SKILL_REVOKED", "Pinned skill was revoked for new activation");
        }
        if (!String.valueOf(chosen.get("contentHash")).equals(live.get("hash"))) {
            throw ApiFailure.conflict("SKILL_HASH_MISMATCH", "Live skill hash drifted");
        }
        return chosen;
    }

    public Map<String, Object> frozenSkill(UUID merchant, UUID runId, String roleCode) {
        var snap = jdbc.sql("""
                SELECT manifest_json::text AS body FROM skill_snapshot
                WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", runId).query(String.class)
                .optional().orElseThrow(() -> ApiFailure.conflict("SKILL_SNAPSHOT_MISSING", "No skill snapshot"));
        return readManifest(snap).stream()
                .filter(e -> roleCode.equals(String.valueOf(e.get("roleCode"))))
                .findFirst()
                .orElseThrow(() -> ApiFailure.conflict("SKILL_ROLE_MISSING", "Role not in snapshot"));
    }

    @Transactional
    public void revoke(String skillId, String version) {
        int updated = jdbc.sql("""
                UPDATE skill_release SET status = 'REVOKED', revoked_at = clock_timestamp()
                WHERE id = :id AND version = :ver AND status = 'PUBLISHED'
                """).param("id", skillId).param("ver", version).update();
        if (updated == 0) {
            throw ApiFailure.conflict("SKILL_NOT_PUBLISHED", "Skill not published");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readList(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json, List.class);
        } catch (Exception ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readManifest(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
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
