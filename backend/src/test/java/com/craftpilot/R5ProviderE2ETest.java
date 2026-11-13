package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.craftpilot.runtime.mcp.McpClient;
import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import javax.imageio.ImageIO;
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
 * Live R5 end-to-end acceptance: chat + OpenAI embedding + Qwen-VL + local MCP + HITL.
 * Never stubs providers. Skips without keys; writes redacted results (no secrets).
 * Excluded from default Surefire — run manually with live .env keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R5ProviderE2ETest {
    private static final String MINIO_USER = "r5-e2e-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r5-e2e";
    private static final String PASSWORD = UUID.randomUUID() + "-r5-e2e";
    private static final Path ENV_FILE = Path.of("..").toAbsolutePath().normalize().resolve(".env");
    private static final Path RESULTS = Path.of("..").toAbsolutePath().normalize()
            .resolve("docs/development/R5-provider-mcp-e2e-results.md");
    private static final Map<String, String> ENV = loadDotEnv(ENV_FILE);
    private static final Map<String, String> E2E = new LinkedHashMap<>();
    private static final AtomicInteger mcpCalls = new AtomicInteger();
    private static HttpServer mcpServer;

    // Qwen-VL requires width/height > 10; distinct 64x64 PNGs like R2.
    private static final byte[] PNG_V1 = solidPng(64, 64, new Color(180, 40, 40));
    private static final byte[] PNG_V2 = solidPng(64, 64, new Color(40, 40, 180));

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

        String openaiKey = firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_API_KEY"),
                System.getenv("CRAFTPILOT_OPENAI_API_KEY"));
        String qwenKey = firstNonBlank(ENV.get("CRAFTPILOT_QWEN_API_KEY"),
                System.getenv("CRAFTPILOT_QWEN_API_KEY"));
        E2E.put("chat_key", "CONFIGURED");
        E2E.put("openai_embedding_key",
                openaiKey != null && !openaiKey.isBlank() ? "CONFIGURED" : "MISSING");
        E2E.put("qwen_vision_key",
                qwenKey != null && !qwenKey.isBlank() ? "CONFIGURED" : "MISSING");
        if (openaiKey == null || openaiKey.isBlank() || qwenKey == null || qwenKey.isBlank()) {
            writeResultsStatic("BLOCKED");
            assumeTrue(false,
                    "CRAFTPILOT_OPENAI_API_KEY and CRAFTPILOT_QWEN_API_KEY required for embedding+vision");
        }

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

        E2E.put("chat_base_url", chatBase);
        E2E.put("chat_model", chatModel);
        E2E.put("mcp_server", "local-loopback-streamable-http");

        config.add("CRAFTPILOT_JDBC_URL", R5ProviderE2ETest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", R5ProviderE2ETest::minioEndpoint);
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
        config.add("craftpilot.openai.api-key", () -> openaiKey);
        config.add("craftpilot.openai.embedding-model",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_EMBEDDING_MODEL"), "text-embedding-3-small"));
        config.add("craftpilot.qwen.api-key", () -> qwenKey);
        config.add("craftpilot.qwen.base-url",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_QWEN_BASE_URL"),
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        config.add("craftpilot.qwen.vision-model",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_QWEN_VISION_MODEL"), "qwen3-vl-plus"));
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
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,'r5-e2e-owner','R5 E2E')")
                .param("id", owner).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'R5 E2E Merchant')").param("id", merchant).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:hash,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("p", owner).param("hash", encoder.encode(PASSWORD)).update();
    }

    @Test
    @Order(1)
    void liveCampaignToMemoryRevokeE2E() throws Exception {
        String endpoint = "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp";
        mcp.registerConnector(merchant, "local-materials", endpoint, List.of("lookup_material"), true);
        mcp.discover(merchant, "local-materials");

        UUID groupId = UUID.fromString(post("/merchants/" + merchant + "/groups", Map.of("name", "R5E2ERoom"))
                .getBody().path("id").asText());
        UUID campaign = UUID.fromString(post("/merchants/" + merchant + "/campaigns", Map.of("name", "R5E2ECamp"))
                .getBody().path("id").asText());
        UUID style = UUID.fromString(post("/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Dress", "category", "wear")).getBody().path("id").asText());

        UUID assetV1 = uploadAsset(PNG_V1, "r5-e2e-v1.png");
        UUID revisionV1 = UUID.fromString(post("/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(assetV1),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v1-e2e")).getBody().path("id").asText());

        UUID surveyId = UUID.fromString(post("/merchants/" + merchant + "/groups/" + groupId + "/surveys",
                Map.of("revisionIds", List.of(revisionV1))).getBody().path("id").asText());

        var cardMsg = post("/merchants/" + merchant + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢面料 FAB-9，建议改版",
                "card", Map.of("revisionId", revisionV1)));
        assertThat(cardMsg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID messageId = UUID.fromString(cardMsg.getBody().path("id").asText());

        var preference = post("/merchants/" + merchant + "/surveys/" + surveyId + "/polls", Map.of(
                "revisionId", revisionV1,
                "kind", "PREFERENCE",
                "question", "Like this style?",
                "options", List.of("Love", "Hate")));
        UUID preferencePoll = UUID.fromString(preference.getBody().path("id").asText());
        UUID love = UUID.fromString(preference.getBody().path("options").get(0).path("id").asText());
        put("/merchants/" + merchant + "/surveys/" + surveyId + "/polls/" + preferencePoll + "/vote",
                Map.of("optionId", love));

        var purchase = post("/merchants/" + merchant + "/surveys/" + surveyId + "/polls", Map.of(
                "revisionId", revisionV1,
                "kind", "PURCHASE_INTENT",
                "question", "Would buy?",
                "options", List.of("Yes", "No")));
        UUID purchasePoll = UUID.fromString(purchase.getBody().path("id").asText());
        UUID yes = UUID.fromString(purchase.getBody().path("options").get(0).path("id").asText());
        put("/merchants/" + merchant + "/surveys/" + surveyId + "/polls/" + purchasePoll + "/vote",
                Map.of("optionId", yes));

        var attribution = post("/merchants/" + merchant + "/messages/" + messageId + "/attribution", Map.of());
        assertThat(attribution.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attribution.getBody().path("status").asText()).isEqualTo("ATTRIBUTED");

        var issues = auth().getForEntity(
                "/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/issues?revisionId=" + revisionV1,
                JsonNode.class);
        assertThat(issues.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.getBody().size()).isGreaterThan(0);
        E2E.put("feedback_issues", String.valueOf(issues.getBody().size()));

        UUID evidenceId = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s AND revision_id = :r
                ORDER BY seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).param("r", revisionV1).query(UUID.class).single();
        var evidence = auth().getForEntity(
                "/api/v1/merchants/" + merchant + "/evidence/" + evidenceId, JsonNode.class);
        assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evidence.getBody().path("visibility").asText()).isEqualTo("ACTIVE");

        var verify = post("/merchants/" + merchant + "/surveys/" + surveyId + "/evidence/verify",
                Map.of("evidenceIds", List.of(evidenceId)));
        assertThat(verify.getBody().path("validIds").size()).isEqualTo(1);
        E2E.put("evidence_verified", "1");

        UUID assetV2 = uploadAsset(PNG_V2, "r5-e2e-v2.png");
        UUID revisionV2 = UUID.fromString(post("/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "parentRevisionId", revisionV1,
                "assetIds", List.of(assetV2),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v2-e2e")).getBody().path("id").asText());
        // No public add-revision API; link V2 into the open survey for vision/retrieval/ticket.
        jdbc.sql("""
                INSERT INTO survey_revision (merchant_id, survey_id, revision_id)
                VALUES (:m, :s, :r)
                """).param("m", merchant).param("s", surveyId).param("r", revisionV2).update();

        post("/merchants/" + merchant + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "V2 改版后颜色更好，面料 FAB-9 仍可",
                "card", Map.of("revisionId", revisionV2)));

        var compare = post("/merchants/" + merchant + "/vision/compare", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV2,
                "surveyId", surveyId));
        assertThat(compare.getStatusCode())
                .as("vision compare body=%s", compare.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(compare.getBody().path("status").asText()).isEqualTo("SUCCEEDED");
        E2E.put("vision_compare_status", compare.getBody().path("status").asText());
        E2E.put("vision_diff_count",
                String.valueOf(compare.getBody().path("visibleDifferences").size()));

        var visionCall = jdbc.sql("""
                SELECT model_name, status, input_tokens, output_tokens, provider_request_id, error_code
                FROM model_call_attempt
                WHERE merchant_id = :m AND purpose = 'vision_compare'
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
        assertThat(visionCall.get("status")).isEqualTo("SUCCEEDED");
        E2E.put("vision_model", visionCall.get("model"));
        E2E.put("vision_status", visionCall.get("status"));
        E2E.put("vision_input_tokens", visionCall.get("in"));
        E2E.put("vision_output_tokens", visionCall.get("out"));
        E2E.put("vision_request_id_redacted", visionCall.get("requestId"));

        var search = post("/merchants/" + merchant + "/surveys/" + surveyId + "/retrieval/search", Map.of(
                "revisionId", revisionV2, "query", "FAB-9 fabric", "topK", 5));
        assertThat(search.getStatusCode())
                .as("retrieval body=%s", search.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(search.getBody().path("retrievalMode").asText()).isIn("hybrid", "vector", "lexical");
        assertThat(search.getBody().path("hits").size()).isGreaterThan(0);
        E2E.put("retrieval_mode", search.getBody().path("retrievalMode").asText());
        E2E.put("retrieval_hits", String.valueOf(search.getBody().path("hits").size()));

        var embedCall = jdbc.sql("""
                SELECT model_name, status, input_tokens, provider_request_id, error_code
                FROM model_call_attempt
                WHERE merchant_id = :m AND purpose = 'embedding'
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchant)
                .query((rs, row) -> Map.of(
                        "model", nullToEmpty(rs.getString("model_name")),
                        "status", nullToEmpty(rs.getString("status")),
                        "tokens", String.valueOf(rs.getObject("input_tokens")),
                        "requestId", redact(rs.getString("provider_request_id")),
                        "error", nullToEmpty(rs.getString("error_code"))))
                .single();
        assertThat(embedCall.get("status")).isEqualTo("SUCCEEDED");
        E2E.put("embedding_model", embedCall.get("model"));
        E2E.put("embedding_status", embedCall.get("status"));
        E2E.put("embedding_input_tokens", embedCall.get("tokens"));
        E2E.put("embedding_request_id_redacted", embedCall.get("requestId"));

        HttpHeaders headers = authHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange(
                "/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "revisionIds", List.of(revisionV1, revisionV2),
                        "objective", "Supervise evidence and material workers; cite real evidence",
                        "workflowId", "launch-supervise-r4"), headers),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = UUID.fromString(created.getBody().path("runId").asText());
        E2E.put("ticket_run_id", runId.toString());

        int claimed = worker.claimAndRun(1);
        assertThat(claimed).isGreaterThanOrEqualTo(1);
        var paused = auth().getForEntity("/api/v1/merchants/" + merchant + "/runtime/runs/" + runId, JsonNode.class);
        String pauseStatus = paused.getBody().path("status").asText();
        String terminal = jdbc.sql("SELECT coalesce(terminal_reason,'') FROM agent_run WHERE id = :id")
                .param("id", runId).query(String.class).single();
        E2E.put("pause_status", pauseStatus);
        E2E.put("terminal_reason", terminal);
        E2E.put("handoff_statuses", jdbc.sql(
                "SELECT string_agg(status || ':' || target_role, ',') FROM handoff WHERE run_id = :r")
                .param("r", runId).query(String.class).optional().orElse(""));
        if (!"WAITING_APPROVAL".equals(pauseStatus)) {
            writeResults(pauseStatus);
        }
        assertThat(pauseStatus)
                .as("expected HITL pause; terminal=%s handoffs=%s", terminal, E2E.get("handoff_statuses"))
                .isEqualTo("WAITING_APPROVAL");

        UUID approvalId = jdbc.sql("""
                SELECT id FROM approval WHERE run_id = :r AND status = 'PENDING' LIMIT 1
                """).param("r", runId).query(UUID.class).single();
        post("/merchants/" + merchant + "/runtime/approvals/" + approvalId + "/decide",
                Map.of("approve", true, "reason", "e2e approve", "expectedVersion", 0));
        post("/merchants/" + merchant + "/runtime/approvals/" + approvalId + "/resume",
                Map.of("commandKey", "e2e-resume-" + runId));
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

        UUID decisionId = jdbc.sql("SELECT id FROM merchant_decision WHERE run_id = :r")
                .param("r", runId).query(UUID.class).optional().orElse(null);
        if (decisionId != null) {
            var decision = post("/merchants/" + merchant + "/runtime/decisions/" + decisionId + "/decide",
                    Map.of("choice", "CONFIRMED", "note", "e2e merchant confirms", "expectedVersion", 0));
            assertThat(decision.getBody().path("status").asText()).isEqualTo("CONFIRMED");
            E2E.put("decision_status", "CONFIRMED");
        } else {
            E2E.put("decision_status", "MISSING");
        }

        UUID proposalId = jdbc.sql("SELECT id FROM memory_proposal WHERE run_id = :r LIMIT 1")
                .param("r", runId).query(UUID.class).optional().orElse(null);
        if (proposalId != null) {
            var published = post("/merchants/" + merchant + "/runtime/memory/proposals/" + proposalId + "/publish",
                    Map.of("approvalId", approvalId));
            assertThat(published.getBody().path("status").asText()).isEqualTo("ACTIVE");
            UUID memoryEntryId = UUID.fromString(published.getBody().path("entryId").asText());
            var memSearch = auth().getForEntity(
                    "/api/v1/merchants/" + merchant + "/runtime/memory?scopeType=SURVEY&scopeKey=" + surveyId,
                    JsonNode.class);
            assertThat(memSearch.getBody().toString()).contains(memoryEntryId.toString());
            var revoked = post("/merchants/" + merchant + "/runtime/memory/entries/" + memoryEntryId
                    + "/revoke?expectedVersion=1", Map.of());
            assertThat(revoked.getBody().path("status").asText()).isEqualTo("REVOKED");
            var afterRevoke = auth().getForEntity(
                    "/api/v1/merchants/" + merchant + "/runtime/memory?scopeType=SURVEY&scopeKey=" + surveyId,
                    JsonNode.class);
            assertThat(afterRevoke.getBody().toString()).doesNotContain(memoryEntryId.toString());
            E2E.put("memory_publish", "ACTIVE");
            E2E.put("memory_revoke", "REVOKED");
        } else {
            E2E.put("memory_publish", "SKIPPED_NO_PROPOSAL");
            E2E.put("memory_revoke", "SKIPPED_NO_PROPOSAL");
        }

        var chatModel = jdbc.sql("""
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

        E2E.put("run_status", status);
        E2E.put("handoff_count", String.valueOf(handoffs));
        E2E.put("join_completed", String.valueOf(joins));
        E2E.put("mcp_tool", "lookup_material");
        E2E.put("mcp_invocations_succeeded", String.valueOf(mcpOk));
        E2E.put("mcp_http_calls", String.valueOf(mcpCalls.get()));
        E2E.put("approval_status", jdbc.sql("SELECT status FROM approval WHERE id = :id")
                .param("id", approvalId).query(String.class).single());
        E2E.put("turns_used", String.valueOf(run.getBody().path("turnsUsed").asInt()));
        E2E.put("tokens_used", String.valueOf(run.getBody().path("tokensUsed").asInt()));
        E2E.put("cost_used_usd", jdbc.sql("SELECT cost_used_usd::text FROM agent_run WHERE id = :id")
                .param("id", runId).query(String.class).single());
        E2E.put("chat_model_call_status", chatModel.get("status"));
        E2E.put("chat_input_tokens", chatModel.get("in"));
        E2E.put("chat_output_tokens", chatModel.get("out"));
        E2E.put("chat_request_id_redacted", chatModel.get("requestId"));
        E2E.put("chat_error", chatModel.get("error"));

        writeResults(status);
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(handoffs).isEqualTo(2L);
        assertThat(joins).isEqualTo(1L);
        assertThat(mcpOk).isGreaterThanOrEqualTo(1L);
        assertThat(decisionId).isNotNull();
        assertThat(proposalId).isNotNull();
    }

    private void writeResults(String status) throws Exception {
        writeResultsStatic(status);
    }

    private static void writeResultsStatic(String status) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# R5 provider + MCP end-to-end results\n\n");
        md.append("Generated by `R5ProviderE2ETest` against live OpenAI-compatible chat, ");
        md.append("OpenAI embedding, Qwen-VL, and a local Streamable HTTP MCP server.\n");
        md.append("Secrets are never written here.\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        for (var e : E2E.entrySet()) {
            md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        md.append("\n**Gate:** Campaign→V1→Survey→vote/feedback/evidence→V2→vision→hybrid retrieval");
        md.append("→ticket/MCP/HITL→decision→memory publish/revoke.\n");
        String outcome;
        if ("BLOCKED".equals(status)) {
            outcome = "BLOCKED";
        } else if ("SUCCEEDED".equals(status)) {
            outcome = "PASSED";
        } else {
            outcome = "FAILED / BLOCKED";
        }
        md.append("\n**Outcome:** ").append(outcome).append("\n");
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, md.toString(), StandardCharsets.UTF_8);
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders()),
                JsonNode.class);
    }

    private TestRestTemplate auth() {
        return rest.withBasicAuth("r5-e2e-owner", PASSWORD);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("r5-e2e-owner", PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private UUID uploadAsset(byte[] bytes, String filename) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
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

    private static byte[] solidPng(int width, int height, Color color) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            g.fillRect(0, 0, width, height);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
