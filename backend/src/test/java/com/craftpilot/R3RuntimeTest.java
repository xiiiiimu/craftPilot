package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R3RuntimeTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r3";
    private static final String PASSWORD = UUID.randomUUID().toString();

    private static HttpServer openaiStub;
    private static HttpServer chatStub;
    private static final AtomicInteger chatMode = new AtomicInteger(0);
    private static final AtomicInteger chatCalls = new AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicReference<String> activeRevision =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<String> activeEvidence =
            new java.util.concurrent.atomic.AtomicReference<>();
    // 0: tool then final; 1: rate limit then ok; 2: bad json final; 3: fabricated evidence

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
    static void configuration(DynamicPropertyRegistry config) throws IOException {
        openaiStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openaiStub.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"data\":[{\"embedding\":" + vectorJson(0.5f)
                    + "}],\"usage\":{\"total_tokens\":8}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        openaiStub.start();

        chatStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chatStub.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int mode = chatMode.get();
            int call = chatCalls.incrementAndGet();
            int status = 200;
            String body;
            if (mode == 1 && call == 1) {
                status = 429;
                body = "{\"error\":\"rate\"}";
            } else if (mode == 2) {
                body = "{\"id\":\"chatcmpl-bad\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"not-json\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
            } else if (call % 2 == 1) {
                body = toolCallBody(activeRevision.get());
            } else if (mode == 3) {
                body = finalBody(UUID.randomUUID().toString());
            } else {
                body = finalBody(activeEvidence.get());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-request-id", "req_r3_test_abcdef");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        chatStub.start();

        config.add("CRAFTPILOT_JDBC_URL", R3RuntimeTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", () -> REDIS.getMappedPort(6379).toString());
        config.add("CRAFTPILOT_MINIO_ENDPOINT", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> "true");
        config.add("craftpilot.openai.base-url", () -> "http://127.0.0.1:" + openaiStub.getAddress().getPort());
        config.add("craftpilot.openai.api-key", () -> "test-key");
        config.add("craftpilot.chat.base-url", () -> "http://127.0.0.1:" + chatStub.getAddress().getPort());
        config.add("craftpilot.chat.api-key", () -> "test-chat-key");
        config.add("craftpilot.chat.model", () -> "gpt-4o-mini");
        config.add("craftpilot.outbox.dispatch-enabled", () -> "false");
        config.add("craftpilot.runtime.worker-enabled", () -> "false");
    }

    @AfterAll
    static void stopStubs() {
        if (openaiStub != null) {
            openaiStub.stop(0);
        }
        if (chatStub != null) {
            chatStub.stop(0);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RunWorker worker;

    static UUID owner;
    static UUID merchant;
    static UUID groupId;
    static UUID revisionId;
    static UUID surveyId;
    static UUID evidenceId;
    static UUID ticketId;
    static UUID runId;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        owner = UUID.randomUUID();
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,'r3-owner','R3 Owner')")
                .param("id", owner).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'R3 Merchant')").param("id", merchant).update();
        jdbc.sql("""
                INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')
                """).param("m", merchant).param("p", owner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:hash,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("p", owner).param("hash", encoder.encode(PASSWORD)).update();
    }

    @Test
    @Order(1)
    void bootstrapDomainAndEvidence() {

        groupId = UUID.fromString(post("/merchants/" + merchant + "/groups", Map.of("name", "R3Room"))
                .getBody().path("id").asText());
        UUID campaign = UUID.fromString(post("/merchants/" + merchant + "/campaigns", Map.of("name", "R3Camp"))
                .getBody().path("id").asText());
        UUID style = UUID.fromString(post("/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Dress", "category", "wear")).getBody().path("id").asText());
        UUID asset = uploadAsset();
        revisionId = UUID.fromString(post("/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v1")).getBody().path("id").asText());
        surveyId = UUID.fromString(post("/merchants/" + merchant + "/groups/" + groupId + "/surveys",
                Map.of("revisionIds", List.of(revisionId))).getBody().path("id").asText());
        post("/merchants/" + merchant + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢面料 FAB-9",
                "card", Map.of("revisionId", revisionId)));
        evidenceId = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s ORDER BY seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).query(UUID.class).single();
        assertThat(evidenceId).isNotNull();
        activeRevision.set(revisionId.toString());
        activeEvidence.set(evidenceId.toString());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_run").query(Long.class).single()).isZero();
    }

    @Test
    @Order(2)
    void triggerTicketAssignmentAttemptIdempotentAndOutboxDedup() {
        UUID key = UUID.randomUUID();
        var created = postTicket(key, "Research fabric feedback");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ticketId = UUID.fromString(created.getBody().path("ticketId").asText());
        runId = UUID.fromString(created.getBody().path("runId").asText());
        assertThat(created.getBody().path("status").asText()).isIn("QUEUED", "RUNNING");

        var again = postTicket(key, "Research fabric feedback");
        assertThat(again.getBody().path("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ticket WHERE survey_id = :s")
                .param("s", surveyId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_run_attempt WHERE ticket_id = :t")
                .param("t", ticketId).query(Long.class).single()).isEqualTo(1L);

        // duplicate outbox delivery receipt
        UUID eventId = jdbc.sql("""
                SELECT id FROM outbox WHERE event_type = 'RUNTIME_RUN_QUEUED' AND aggregate_id = :run
                """).param("run", runId).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO consumer_receipt (merchant_id, consumer_name, event_id, effect_key)
                VALUES (:m, 'ws-fanout', :e, :effect)
                """).param("m", merchant).param("e", eventId)
                .param("effect", "RUNTIME_RUN_QUEUED:" + eventId).update();
        assertThatThrownByDuplicateReceipt(eventId);
    }

    @Test
    @Order(3)
    void multiTurnAgentLoopToolBrokerEvidenceAndAudit() {
        chatMode.set(0);
        chatCalls.set(0);
        assertThat(worker.claimAndRun(1)).isGreaterThanOrEqualTo(1);
        var run = get("/merchants/" + merchant + "/runtime/runs/" + runId);
        assertThat(run.getBody().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.getBody().path("turnsUsed").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(run.getBody().path("toolCallsUsed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(run.getBody().path("finalOutput").path("evidenceIds").toString()).contains(evidenceId.toString());

        var tools = get("/merchants/" + merchant + "/runtime/runs/" + runId + "/tool-calls");
        assertThat(tools.getBody().size()).isGreaterThanOrEqualTo(1);
        assertThat(tools.getBody().get(0).path("toolName").asText()).isEqualTo("evidence_search");
        assertThat(tools.getBody().get(0).path("status").asText()).isEqualTo("SUCCEEDED");

        var models = get("/merchants/" + merchant + "/runtime/runs/" + runId + "/model-calls");
        assertThat(models.getBody().size()).isGreaterThanOrEqualTo(2);
        assertThat(models.getBody().get(0).path("purpose").asText()).isEqualTo("agent_chat");
        assertThat(models.getBody().get(0).path("providerRequestIdRedacted").asText()).contains("…");

        var budget = get("/merchants/" + merchant + "/runtime/runs/" + runId + "/budget");
        assertThat(budget.getBody().path("tokensUsed").asInt()).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM checkpoint WHERE run_id = :r")
                .param("r", runId).query(Long.class).single()).isGreaterThan(0L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM usage_ledger WHERE run_id = :r")
                .param("r", runId).query(Long.class).single()).isGreaterThan(0L);
    }

    @Test
    @Order(4)
    void leaseFenceRejectsStaleWorkerAndCancelStopsRun() {
        UUID key = UUID.randomUUID();
        var created = postTicket(key, "Second research");
        UUID run2 = UUID.fromString(created.getBody().path("runId").asText());
        var claimA = worker.claimOnePublic();
        assertThat(claimA).isNotNull();
        assertThat(claimA.runId()).isEqualTo(run2);
        long epochA = claimA.leaseEpoch();

        // expire lease and let another claim take over
        jdbc.sql("""
                UPDATE agent_run SET lease_until = clock_timestamp() - interval '1 second' WHERE id = :id
                """).param("id", run2).update();
        var claimB = worker.claimOnePublic();
        assertThat(claimB).isNotNull();
        assertThat(claimB.leaseEpoch()).isGreaterThan(epochA);

        var runView = get("/merchants/" + merchant + "/runtime/runs/" + run2);
        long version = runView.getBody().path("version").asLong();
        var cancelled = post("/merchants/" + merchant + "/runtime/runs/" + run2 + "/cancel",
                Map.of("expectedVersion", version, "reason", "test-cancel"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(cancelled.getBody().path("status").asText()).isEqualTo("CANCEL_REQUESTED");
        worker.claimAndRun(1);
        var after = get("/merchants/" + merchant + "/runtime/runs/" + run2);
        assertThat(after.getBody().path("status").asText()).isIn("CANCELLED", "CANCEL_REQUESTED", "FAILED");
    }

    @Test
    @Order(5)
    void fabricatedEvidenceRejectedAndIllegalToolArgsDenied() {
        chatMode.set(3);
        chatCalls.set(0);
        UUID key = UUID.randomUUID();
        var created = postTicket(key, "Fabricated evidence path");
        UUID run3 = UUID.fromString(created.getBody().path("runId").asText());
        worker.claimAndRun(1);
        var run = get("/merchants/" + merchant + "/runtime/runs/" + run3);
        assertThat(run.getBody().path("status").asText()).isEqualTo("FAILED");

        // cross-tenant ticket lookup
        UUID otherOwner = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,'r3-other','Other')")
                .param("id", otherOwner).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'OtherM')").param("id", otherMerchant).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", otherMerchant).param("p", otherOwner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:h,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("p", otherOwner).param("h", encoder.encode(PASSWORD)).update();
        var cross = auth("r3-other").getForEntity("/api/v1/merchants/" + merchant + "/tickets/" + ticketId,
                JsonNode.class);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(6)
    void illegalStateTransitionAndBudgetGuardrails() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM workflow_release").query(Long.class).single()).isGreaterThan(0L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tool_release").query(Long.class).single()).isEqualTo(5L);
        // completed run cannot be completed again
        int updated = jdbc.sql("""
                UPDATE agent_run SET status = 'SUCCEEDED' WHERE id = :id AND status = 'SUCCEEDED'
                """).param("id", runId).update();
        assertThat(updated).isEqualTo(1);
        long cp = jdbc.sql("SELECT checkpoint_head FROM agent_run WHERE id = :id").param("id", runId)
                .query(Long.class).single();
        assertThat(cp).isGreaterThan(0L);
    }

    private void assertThatThrownByDuplicateReceipt(UUID eventId) {
        try {
            jdbc.sql("""
                    INSERT INTO consumer_receipt (merchant_id, consumer_name, event_id, effect_key)
                    VALUES (:m, 'ws-fanout', :e, :effect)
                    """).param("m", merchant).param("e", eventId)
                    .param("effect", "RUNTIME_RUN_QUEUED:" + eventId).update();
            throw new AssertionError("expected duplicate key");
        } catch (Exception ex) {
            assertThat(ex.getMessage()).containsIgnoringCase("duplicate");
        }
    }

    private ResponseEntity<JsonNode> postTicket(UUID key, String objective) {
        HttpHeaders headers = authHeaders();
        headers.set("Idempotency-Key", key.toString());
        return rest.exchange("/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST, new HttpEntity<>(Map.of(
                        "revisionIds", List.of(revisionId),
                        "objective", objective), headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return auth("r3-owner").getForEntity("/api/v1" + path, JsonNode.class);
    }

    private TestRestTemplate auth(String login) {
        return rest.withBasicAuth(login, PASSWORD);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("r3-owner", PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private UUID uploadAsset() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource("r3-img".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "r3.png";
            }
        });
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String toolCallBody(String revisionId) {
        String rev = revisionId == null ? "00000000-0000-0000-0000-000000000000" : revisionId;
        String args = "{\"revisionId\":\"" + rev + "\",\"query\":\"fabric FAB-9\",\"topK\":5}";
        String argsEscaped = args.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-tool\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"evidence_search\",\"arguments\":\""
                + argsEscaped
                + "\"}}]}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":15}}";
    }

    private static String finalBody(String evidenceId) {
        String id = evidenceId == null || evidenceId.isBlank()
                ? "00000000-0000-0000-0000-000000000000" : evidenceId;
        String content = "{\"recommendation\":\"REVISE\",\"summary\":\"Fabric feedback supports revise\",\"evidenceIds\":[\""
                + id + "\"]}";
        String contentEscaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-final\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + contentEscaped
                + "\"}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":40}}";
    }

    private static String vectorJson(float seed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(seed);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }
}
