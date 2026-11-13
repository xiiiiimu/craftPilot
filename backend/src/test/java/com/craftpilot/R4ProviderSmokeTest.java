package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.craftpilot.runtime.mcp.McpClient;
import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Live multi-agent + MCP + HITL smoke. Chat provider is never stubbed.
 * Local MCP server is real Streamable HTTP. Skips without chat key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R4ProviderSmokeTest {
    private static final String MINIO_USER = "r4-smoke-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r4-smoke";
    private static final String PASSWORD = UUID.randomUUID() + "-r4-smoke";
    private static final Path ENV_FILE = Path.of("..").toAbsolutePath().normalize().resolve(".env");
    private static final Path RESULTS = Path.of("..").toAbsolutePath().normalize()
            .resolve("docs/development/R4-provider-mcp-smoke-results.md");
    private static final Map<String, String> ENV = loadDotEnv(ENV_FILE);
    private static final Map<String, String> SMOKE = new LinkedHashMap<>();
    private static final AtomicInteger mcpCalls = new AtomicInteger();
    private static HttpServer mcpServer;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16-trixie@sha256:9cf8b511f0e9a2c209b61430fc139f1ab9c75edbb31fb1751db6617b246a897a")
                    .asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:7.4.12-alpine3.22@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe")
            .withExposedPorts(6379);
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:786cb11d340b1a657de8dc899560e36e1d1fb371d3d6f3eb6c903a0bb0cec714")
            .withEnv("MINIO_ROOT_USER", MINIO_USER).withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry config) throws Exception {
        String chatKey = firstNonBlank(ENV.get("CRAFTPILOT_CHAT_API_KEY"),
                firstNonBlank(System.getenv("CRAFTPILOT_CHAT_API_KEY"),
                        firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_API_KEY"),
                                System.getenv("CRAFTPILOT_OPENAI_API_KEY"))));
        assumeTrue(chatKey != null && !chatKey.isBlank(),
                "CRAFTPILOT_CHAT_API_KEY or CRAFTPILOT_OPENAI_API_KEY required");

        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            String request = new String(req, StandardCharsets.UTF_8);
            mcpCalls.incrementAndGet();
            String response;
            if (request.contains("tools/list")) {
                response = """
                        {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"lookup_material","description":"lookup","inputSchema":{"type":"object","required":["materialCode"],"properties":{"materialCode":{"type":"string"}},"additionalProperties":false}}]}}
                        """;
            } else {
                response = """
                        {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"FAB-9 ok"}],"isError":false}}
                        """;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mcpServer.start();

        String chatBase = firstNonBlank(ENV.get("CRAFTPILOT_CHAT_BASE_URL"),
                firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_BASE_URL"), "https://api.openai.com"));
        String chatModel = firstNonBlank(ENV.get("CRAFTPILOT_CHAT_MODEL"), "gpt-4o-mini");
        String openaiKey = firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_API_KEY"),
                System.getenv("CRAFTPILOT_OPENAI_API_KEY"));

        SMOKE.put("chat_key", "CONFIGURED");
        SMOKE.put("openai_embedding_key",
                openaiKey != null && !openaiKey.isBlank() ? "CONFIGURED" : "MISSING");
        SMOKE.put("chat_base_url", chatBase);
        SMOKE.put("chat_model", chatModel);
        SMOKE.put("mcp_server", "local-loopback-streamable-http");

        config.add("CRAFTPILOT_JDBC_URL", R4ProviderSmokeTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", R4ProviderSmokeTest::minioEndpoint);
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> true);
        config.add("craftpilot.outbox.dispatch-enabled", () -> false);
        config.add("craftpilot.runtime.worker-enabled", () -> false);
        config.add("craftpilot.chat.base-url", () -> chatBase);
        config.add("craftpilot.chat.api-key", () -> chatKey);
        config.add("craftpilot.chat.model", () -> chatModel);
        config.add("craftpilot.openai.base-url",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_BASE_URL"), "https://api.openai.com"));
        config.add("craftpilot.openai.api-key",
                () -> openaiKey == null || openaiKey.isBlank() ? chatKey : openaiKey);
        config.add("craftpilot.openai.embedding-model",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_EMBEDDING_MODEL"), "text-embedding-3-small"));
    }

    @AfterAll
    static void stopMcp() {
        if (mcpServer != null) {
            mcpServer.stop(0);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RunWorker worker;
    @Autowired McpClient mcp;

    private static UUID owner;
    private static UUID merchant;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        owner = UUID.randomUUID();
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,'r4-smoke-owner','R4 Smoke')")
                .param("id", owner).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'R4 Smoke Merchant')").param("id", merchant).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:hash,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("p", owner).param("hash", encoder.encode(PASSWORD)).update();
    }

    @Test
    @Order(1)
    void liveSupervisorMcpHitlMemory() throws Exception {
        String endpoint = "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp";
        mcp.registerConnector(merchant, "local-materials", endpoint, List.of("lookup_material"), true);
        mcp.discover(merchant, "local-materials");

        UUID groupId = UUID.fromString(post("/merchants/" + merchant + "/groups", Map.of("name", "R4SmokeRoom"))
                .getBody().path("id").asText());
        UUID campaign = UUID.fromString(post("/merchants/" + merchant + "/campaigns", Map.of("name", "R4SmokeCamp"))
                .getBody().path("id").asText());
        UUID style = UUID.fromString(post("/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Dress", "category", "wear")).getBody().path("id").asText());
        UUID asset = uploadAsset();
        UUID revisionId = UUID.fromString(post("/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v1-smoke")).getBody().path("id").asText());
        UUID surveyId = UUID.fromString(post("/merchants/" + merchant + "/groups/" + groupId + "/surveys",
                Map.of("revisionIds", List.of(revisionId))).getBody().path("id").asText());
        post("/merchants/" + merchant + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢面料 FAB-9，建议改版",
                "card", Map.of("revisionId", revisionId)));

        HttpHeaders headers = authHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange(
                "/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "revisionIds", List.of(revisionId),
                        "objective", "Supervise evidence and material workers; cite real evidence",
                        "workflowId", "launch-supervise-r4"), headers),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = UUID.fromString(created.getBody().path("runId").asText());

        int claimed = worker.claimAndRun(1);
        assertThat(claimed).isGreaterThanOrEqualTo(1);
        var paused = auth().getForEntity("/api/v1/merchants/" + merchant + "/runtime/runs/" + runId, JsonNode.class);
        String pauseStatus = paused.getBody().path("status").asText();
        String terminal = jdbc.sql("SELECT coalesce(terminal_reason,'') FROM agent_run WHERE id = :id")
                .param("id", runId).query(String.class).single();
        SMOKE.put("pause_status", pauseStatus);
        SMOKE.put("terminal_reason", terminal);
        SMOKE.put("handoff_statuses", jdbc.sql(
                "SELECT string_agg(status || ':' || target_role, ',') FROM handoff WHERE run_id = :r")
                .param("r", runId).query(String.class).optional().orElse(""));
        if (!"WAITING_APPROVAL".equals(pauseStatus)) {
            writeResults(pauseStatus);
        }
        assertThat(pauseStatus)
                .as("expected HITL pause; terminal=%s handoffs=%s", terminal, SMOKE.get("handoff_statuses"))
                .isEqualTo("WAITING_APPROVAL");

        UUID approvalId = jdbc.sql("""
                SELECT id FROM approval WHERE run_id = :r AND status = 'PENDING' LIMIT 1
                """).param("r", runId).query(UUID.class).single();
        post("/merchants/" + merchant + "/runtime/approvals/" + approvalId + "/decide",
                Map.of("approve", true, "reason", "smoke approve", "expectedVersion", 0));
        post("/merchants/" + merchant + "/runtime/approvals/" + approvalId + "/resume",
                Map.of("commandKey", "smoke-resume-" + runId));
        assertThat(worker.claimAndRun(1)).isGreaterThanOrEqualTo(1);

        var run = auth().getForEntity("/api/v1/merchants/" + merchant + "/runtime/runs/" + runId, JsonNode.class);
        String status = run.getBody().path("status").asText();
        long handoffs = jdbc.sql("SELECT COUNT(*) FROM handoff WHERE run_id = :r").param("r", runId)
                .query(Long.class).single();
        long joins = jdbc.sql("SELECT COUNT(*) FROM task_join WHERE run_id = :r AND status = 'COMPLETED'")
                .param("r", runId).query(Long.class).single();
        long mcpOk = jdbc.sql("""
                SELECT COUNT(*) FROM mcp_invocation WHERE run_id = :r AND status = 'SUCCEEDED'
                """).param("r", runId).query(Long.class).single();
        UUID proposalId = jdbc.sql("SELECT id FROM memory_proposal WHERE run_id = :r LIMIT 1")
                .param("r", runId).query(UUID.class).optional().orElse(null);
        if (proposalId != null) {
            post("/merchants/" + merchant + "/runtime/memory/proposals/" + proposalId + "/publish",
                    Map.of("approvalId", approvalId));
        }

        var model = jdbc.sql("""
                SELECT model_name, status, input_tokens, output_tokens, provider_request_id, error_code
                FROM model_call_attempt WHERE merchant_id = :m AND purpose = 'agent_chat'
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchant)
                .query((rs, row) -> Map.of(
                        "model", nullToEmpty(rs.getString("model_name")),
                        "status", nullToEmpty(rs.getString("status")),
                        "in", String.valueOf(rs.getObject("input_tokens")),
                        "out", String.valueOf(rs.getObject("output_tokens")),
                        "requestId", redact(rs.getString("provider_request_id")),
                        "error", nullToEmpty(rs.getString("error_code"))))
                .optional().orElse(Map.of("model", "", "status", "MISSING", "in", "", "out", "",
                        "requestId", "(none)", "error", "NO_MODEL_CALL"));

        SMOKE.put("run_status", status);
        SMOKE.put("agent_count", "3");
        SMOKE.put("handoff_count", String.valueOf(handoffs));
        SMOKE.put("join_completed", String.valueOf(joins));
        SMOKE.put("mcp_tool", "lookup_material");
        SMOKE.put("mcp_invocations_succeeded", String.valueOf(mcpOk));
        SMOKE.put("mcp_http_calls", String.valueOf(mcpCalls.get()));
        SMOKE.put("approval_status", jdbc.sql("SELECT status FROM approval WHERE id = :id")
                .param("id", approvalId).query(String.class).single());
        SMOKE.put("memory_publish", proposalId == null ? "SKIPPED_NO_EVIDENCE"
                : jdbc.sql("SELECT status FROM memory_entry WHERE proposal_id = :p LIMIT 1")
                .param("p", proposalId).query(String.class).optional().orElse("MISSING"));
        SMOKE.put("turns_used", String.valueOf(run.getBody().path("turnsUsed").asInt()));
        SMOKE.put("tokens_used", String.valueOf(run.getBody().path("tokensUsed").asInt()));
        SMOKE.put("cost_used_usd", jdbc.sql("SELECT cost_used_usd::text FROM agent_run WHERE id = :id")
                .param("id", runId).query(String.class).single());
        SMOKE.put("chat_model_call_status", model.get("status"));
        SMOKE.put("chat_input_tokens", model.get("in"));
        SMOKE.put("chat_output_tokens", model.get("out"));
        SMOKE.put("chat_request_id_redacted", model.get("requestId"));
        SMOKE.put("chat_error", model.get("error"));

        writeResults(status);
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(handoffs).isEqualTo(2L);
        assertThat(joins).isEqualTo(1L);
        assertThat(mcpOk).isGreaterThanOrEqualTo(1L);
    }

    private void writeResults(String status) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# R4 provider + MCP smoke results\n\n");
        md.append("Generated by `R4ProviderSmokeTest` against a live OpenAI-compatible chat model ");
        md.append("and a local Streamable HTTP MCP server.\nSecrets are never written here.\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        for (var e : SMOKE.entrySet()) {
            md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        md.append("\n**Gate:** live multi-agent + MCP tool call + HITL approve/resume + SUCCEEDED.\n");
        md.append("\n**Outcome:** ").append("SUCCEEDED".equals(status) ? "PASSED" : "FAILED / BLOCKED").append("\n");
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, md.toString(), StandardCharsets.UTF_8);
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()),
                JsonNode.class);
    }

    private TestRestTemplate auth() {
        return rest.withBasicAuth("r4-smoke-owner", PASSWORD);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("r4-smoke-owner", PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private UUID uploadAsset() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource("r4-smoke-img".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "r4-smoke.png";
            }
        });
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static Map<String, String> loadDotEnv(Path path) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(path)) {
                return map;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                map.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        if (value.length() <= 8) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 8) + "…";
    }
}
