package com.craftpilot.runtime.release;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves the instructions an agent runs with: the latest version ({@code agent_spec}) unless a version is pinned
 * through {@link RuntimeVersions}, in which case it comes from {@code agent_spec_revision}. Global configuration
 * tables, so there is no merchant_id to filter on.
 */
@Component
public class AgentSpecResolver {
    public static final String RESEARCH_AGENT = "research-agent-r3";

    public record ResolvedSpec(String id, String version, String contentHash, String instructions) {}

    private final JdbcClient jdbc;
    private final RuntimeVersions versions;

    public AgentSpecResolver(JdbcClient jdbc, RuntimeVersions versions) {
        this.jdbc = jdbc;
        this.versions = versions;
    }

    public ResolvedSpec research() {
        return resolve(RESEARCH_AGENT, versions.researchPromptVersion());
    }

    public ResolvedSpec resolve(String specId, Optional<String> version) {
        if (version.isEmpty()) {
            return jdbc.sql("SELECT id, version, content_hash, instructions FROM agent_spec WHERE id = :id")
                    .param("id", specId)
                    .query((rs, i) -> new ResolvedSpec(rs.getString("id"), rs.getString("version"),
                            rs.getString("content_hash"), rs.getString("instructions")))
                    .optional().orElseThrow(() -> new IllegalStateException("unknown agent spec " + specId));
        }
        return jdbc.sql("""
                SELECT agent_spec_id, version, content_hash, instructions FROM agent_spec_revision
                WHERE agent_spec_id = :id AND version = :version
                """).param("id", specId).param("version", version.get())
                .query((rs, i) -> new ResolvedSpec(rs.getString("agent_spec_id"), rs.getString("version"),
                        rs.getString("content_hash"), rs.getString("instructions")))
                .optional().orElseThrow(() -> new IllegalStateException(
                        "agent spec " + specId + " has no version " + version.get()));
    }
}
