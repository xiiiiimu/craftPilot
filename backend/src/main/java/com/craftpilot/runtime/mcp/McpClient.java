package com.craftpilot.runtime.mcp;

import com.craftpilot.common.ApiFailure;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class McpClient {
    private static final String PROTOCOL = "2026-07-28";
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal", "169.254.169.254", "metadata");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public McpClient(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(15))
                .followRedirects(false)
                .build();
    }

    public record ToolInfo(String name, String inputSchemaHash, Map<String, Object> inputSchema) {}
    public record CallResult(UUID invocationId, boolean ok, Map<String, Object> result, String error) {}

    public List<ToolInfo> discover(UUID merchant, String connectorId) {
        var cfg = requireActiveConnector(merchant, connectorId);
        validateEndpoint(String.valueOf(cfg.get("endpoint")), Boolean.TRUE.equals(cfg.get("allowLoopback")));
        Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of());
        JsonNode root = post(String.valueOf(cfg.get("endpoint")), "tools/list", null, rpc);
        JsonNode tools = root.path("result").path("tools");
        if (!tools.isArray()) {
            throw ApiFailure.unavailable("MCP_PROTOCOL_ERROR", "tools/list missing tools");
        }
        List<ToolInfo> out = new ArrayList<>();
        String catalogVersion = "v-" + System.currentTimeMillis();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            Map<String, Object> schema = readMap(tool.path("inputSchema"));
            String hash = sha(write(schema));
            out.add(new ToolInfo(name, hash, schema));
            jdbc.sql("""
                    INSERT INTO connector_catalog
                    (merchant_id, connector_id, tool_name, input_schema_hash, input_schema, catalog_version, status)
                    VALUES (:m, :c, :name, :hash, CAST(:schema AS jsonb), :ver, 'ACTIVE')
                    ON CONFLICT (merchant_id, connector_id, tool_name, catalog_version) DO NOTHING
                    """).param("m", merchant).param("c", connectorId).param("name", name)
                    .param("hash", hash).param("schema", write(schema)).param("ver", catalogVersion).update();
        }
        jdbc.sql("""
                UPDATE connector_config SET catalog_version = :ver, catalog_hash = :hash
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", connectorId).param("ver", catalogVersion)
                .param("hash", sha(write(out.stream().map(ToolInfo::name).toList()))).update();
        return out;
    }

    public CallResult call(UUID merchant, UUID surveyId, UUID runId, UUID taskId, String connectorId,
                           String toolName, Map<String, Object> arguments) {
        var cfg = requireActiveConnector(merchant, connectorId);
        validateEndpoint(String.valueOf(cfg.get("endpoint")), Boolean.TRUE.equals(cfg.get("allowLoopback")));
        @SuppressWarnings("unchecked")
        List<String> whitelist = (List<String>) cfg.get("whitelist");
        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(toolName)) {
            throw ApiFailure.forbidden();
        }
        boolean catalogOk = jdbc.sql("""
                SELECT 1 FROM connector_catalog
                WHERE merchant_id = :m AND connector_id = :c AND tool_name = :name AND status = 'ACTIVE'
                ORDER BY catalog_version DESC LIMIT 1
                """).param("m", merchant).param("c", connectorId).param("name", toolName)
                .query(Integer.class).optional().isPresent();
        if (!catalogOk) {
            throw ApiFailure.conflict("MCP_TOOL_UNKNOWN", "Tool not in approved catalog");
        }
        UUID invocationId = UUID.randomUUID();
        String requestHash = sha(connectorId + "|" + toolName + "|" + write(arguments));
        jdbc.sql("""
                INSERT INTO mcp_invocation
                (merchant_id, id, survey_id, run_id, task_id, connector_id, tool_name, request_hash, status)
                VALUES (:m, :id, :survey, :run, :task, :c, :name, :hash, 'REQUESTED')
                """).param("m", merchant).param("id", invocationId).param("survey", surveyId).param("run", runId)
                .param("task", taskId).param("c", connectorId).param("name", toolName).param("hash", requestHash)
                .update();
        try {
            Map<String, Object> rpc = Map.of(
                    "jsonrpc", "2.0",
                    "id", invocationId.toString(),
                    "method", "tools/call",
                    "params", Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments));
            JsonNode root = post(String.valueOf(cfg.get("endpoint")), "tools/call", toolName, rpc);
            if (root.has("error")) {
                mark(merchant, invocationId, "FAILED", 200, root.path("error").path("code").asText("RPC_ERROR"), null);
                return new CallResult(invocationId, false, Map.of(), root.path("error").path("message").asText("error"));
            }
            JsonNode result = root.path("result");
            boolean isError = result.path("isError").asBoolean(false);
            Map<String, Object> body = readMap(result);
            mark(merchant, invocationId, isError ? "FAILED" : "SUCCEEDED", 200, isError ? "TOOL_IS_ERROR" : null, body);
            return new CallResult(invocationId, !isError, body, isError ? "TOOL_IS_ERROR" : null);
        } catch (ApiFailure ex) {
            mark(merchant, invocationId, "REJECTED", null, ex.code(), null);
            throw ex;
        } catch (RuntimeException ex) {
            mark(merchant, invocationId, "TIMEOUT", null, ex.getClass().getSimpleName(), null);
            throw ApiFailure.unavailable("MCP_UNAVAILABLE", "MCP call failed");
        }
    }

    public void registerConnector(UUID merchant, String id, String endpointUrl, List<String> whitelist,
                                  boolean allowLoopback) {
        validateEndpoint(endpointUrl, allowLoopback);
        jdbc.sql("""
                INSERT INTO connector_config
                (merchant_id, id, endpoint_url, protocol_version, tool_whitelist, status, allow_loopback)
                VALUES (:m, :id, :url, :proto, CAST(:wl AS jsonb), 'ACTIVE', :loop)
                ON CONFLICT (merchant_id, id) DO UPDATE
                SET endpoint_url = EXCLUDED.endpoint_url, tool_whitelist = EXCLUDED.tool_whitelist,
                    status = 'ACTIVE', allow_loopback = EXCLUDED.allow_loopback, revoked_at = NULL
                """).param("m", merchant).param("id", id).param("url", endpointUrl).param("proto", PROTOCOL)
                .param("wl", write(whitelist == null ? List.of() : whitelist)).param("loop", allowLoopback)
                .update();
    }

    private Map<String, Object> requireActiveConnector(UUID merchant, String connectorId) {
        return jdbc.sql("""
                SELECT endpoint_url, tool_whitelist::text AS wl, allow_loopback, status
                FROM connector_config WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", connectorId)
                .query((rs, i) -> {
                    if (!"ACTIVE".equals(rs.getString("status"))) {
                        throw ApiFailure.conflict("MCP_CONNECTOR_REVOKED", "Connector revoked");
                    }
                    return Map.<String, Object>of(
                            "endpoint", rs.getString("endpoint_url"),
                            "whitelist", readList(rs.getString("wl")),
                            "allowLoopback", rs.getBoolean("allow_loopback"));
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    private JsonNode post(String endpoint, String method, String toolName, Map<String, Object> body) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(write(body), MediaType.get("application/json")))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", PROTOCOL)
                    .header("Mcp-Method", method);
            if (toolName != null) {
                builder.header("Mcp-Name", toolName);
            }
            try (var response = http.newCall(builder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw ApiFailure.unavailable("MCP_HTTP_" + response.code(), "MCP HTTP failure");
                }
                return mapper.readTree(responseBody);
            }
        } catch (ApiFailure ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void validateEndpoint(String endpointUrl, boolean allowLoopback) {
        URI uri;
        try {
            uri = URI.create(endpointUrl);
        } catch (Exception ex) {
            throw ApiFailure.invalid("Invalid MCP endpoint");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw ApiFailure.forbidden();
        }
        String host = uri.getHost();
        if (host == null || BLOCKED_HOSTS.contains(host.toLowerCase())) {
            throw ApiFailure.forbidden();
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || address.isSiteLocalAddress() || address.isLoopbackAddress()) {
                if (!(allowLoopback && address.isLoopbackAddress())) {
                    throw ApiFailure.forbidden();
                }
            }
        } catch (ApiFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiFailure.invalid("MCP host resolution failed");
        }
    }

    private void mark(UUID merchant, UUID id, String status, Integer http, String error, Map<String, Object> result) {
        jdbc.sql("""
                UPDATE mcp_invocation SET status = :status, http_status = :http, error_code = :err,
                       result_json = CAST(:body AS jsonb), completed_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", id).param("status", status).param("http", http)
                .param("err", error).param("body", result == null ? "{}" : write(result)).update();
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
    private Map<String, Object> readMap(JsonNode node) {
        try {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return Map.of();
            }
            return mapper.convertValue(node, Map.class);
        } catch (Exception ex) {
            return Map.of();
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
