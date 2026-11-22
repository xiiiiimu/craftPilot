package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * P0-4 (model pricing from configuration, estimated usage when the provider omits it) and P0-5 (model, tool and
 * context-build latency persisted per turn / invocation and exported as Micrometer timers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class EvalInstrumentationTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-instr";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String MODEL = "eval-priced-model";
    private static final BigDecimal INPUT_PER_MTOK = new BigDecimal("2");
    private static final BigDecimal OUTPUT_PER_MTOK = new BigDecimal("8");
    private static final long STUB_DELAY_MS = 150;
    private static final int MAX_OUTPUT_TOKENS = 800;

    private static HttpServer openaiStub;
    private static HttpServer chatStub;
    private static final AtomicBoolean omitUsage = new AtomicBoolean(false);
    private static final AtomicReference<String> activeRevision = new AtomicReference<>();
    private static final AtomicReference<String> activeEvidence = new AtomicReference<>();

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
            respond(exchange, "{\"data\":[{\"embedding\":" + vectorJson() + "}],\"usage\":{\"total_tokens\":8}}");
        });
        openaiStub.start();

        chatStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chatStub.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(STUB_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            boolean hasToolResult = request.contains("\"role\":\"tool\"");
            String usage = omitUsage.get() ? "" : hasToolResult
                    ? ",\"usage\":{\"prompt_tokens\":2000,\"completion_tokens\":250}"
                    : ",\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}";
            String body = hasToolResult ? finalBody(activeEvidence.get(), usage) : toolCallBody(activeRevision.get(), usage);
            exchange.getResponseHeaders().add("x-request-id", "req_instr_" + UUID.randomUUID());
            respond(exchange, body);
        });
        chatStub.start();

        config.add("CRAFTPILOT_JDBC_URL", EvalInstrumentationTest::jdbcUrl);
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
        config.add("craftpilot.chat.model", () -> MODEL);
        config.add("craftpilot.chat.pricing.models[" + MODEL + "].input-usd-per-mtok", INPUT_PER_MTOK::toPlainString);
        config.add("craftpilot.chat.pricing.models[" + MODEL + "].output-usd-per-mtok", OUTPUT_PER_MTOK::toPlainString);
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
    @Autowired RunWorker worker;
    @Autowired MeterRegistry meters;

    static UUID merchant;
    static UUID surveyId;
    static UUID revisionId;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio, @Autowired TestRestTemplate rest) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        UUID owner = UUID.randomUUID();
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,'instr-owner','Instr')")
                .param("id", owner).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'Instr Merchant')").param("id", merchant).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:h,clock_timestamp()-interval '1 day', clock_timestamp()+interval '1 day')
                """).param("p", owner).param("h", encoder.encode(PASSWORD)).update();

        UUID group = id(post(rest, "/merchants/" + merchant + "/groups", Map.of("name", "InstrRoom")));
        UUID campaign = id(post(rest, "/merchants/" + merchant + "/campaigns", Map.of("name", "InstrCamp")));
        UUID style = id(post(rest, "/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Dress", "category", "wear")));
        UUID asset = uploadAsset(rest);
        revisionId = id(post(rest, "/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", false)),
                "notes", "v1")));
        surveyId = id(post(rest, "/merchants/" + merchant + "/groups/" + group + "/surveys",
                Map.of("revisionIds", List.of(revisionId))));
        post(rest, "/merchants/" + merchant + "/groups/" + group + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(), "text", "喜欢面料 FAB-9", "card", Map.of("revisionId", revisionId)));
        UUID evidence = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s ORDER BY seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).query(UUID.class).single();
        activeRevision.set(revisionId.toString());
        activeEvidence.set(evidence.toString());
    }

    @Test
    void settleAndReservationUseConfiguredModelPrice() {
        omitUsage.set(false);
        UUID run = runResearch("priced run");

        List<Map<String, Object>> ledger = jdbc.sql("""
                SELECT tokens_in, tokens_out, cost_usd, accuracy FROM usage_ledger
                WHERE merchant_id = :m AND run_id = :run ORDER BY created_at
                """).param("m", merchant).param("run", run).query().listOfRows();
        assertThat(ledger).hasSize(2);
        for (Map<String, Object> row : ledger) {
            assertThat(row.get("accuracy")).isEqualTo("EXACT");
            assertThat((BigDecimal) row.get("cost_usd"))
                    .isEqualByComparingTo(price((Integer) row.get("tokens_in"), (Integer) row.get("tokens_out")));
        }

        List<Map<String, Object>> reservations = jdbc.sql("""
                SELECT tokens_reserved, cost_reserved_usd FROM budget_reservation
                WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", run).query().listOfRows();
        assertThat(reservations).hasSize(2);
        for (Map<String, Object> row : reservations) {
            int reserved = (Integer) row.get("tokens_reserved");
            assertThat((BigDecimal) row.get("cost_reserved_usd"))
                    .isEqualByComparingTo(price(reserved - MAX_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS));
        }
    }

    @Test
    void missingProviderUsageIsEstimatedAndPricedInsteadOfFlatFee() {
        omitUsage.set(true);
        UUID run = runResearch("usage missing");

        List<Map<String, Object>> ledger = jdbc.sql("""
                SELECT tokens_in, tokens_out, cost_usd, accuracy FROM usage_ledger
                WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", run).query().listOfRows();
        assertThat(ledger).hasSize(2);
        for (Map<String, Object> row : ledger) {
            assertThat(row.get("accuracy")).isEqualTo("ESTIMATED");
            assertThat((Integer) row.get("tokens_in")).isPositive();
            assertThat((Integer) row.get("tokens_out")).isPositive();
            assertThat((BigDecimal) row.get("cost_usd"))
                    .isEqualByComparingTo(price((Integer) row.get("tokens_in"), (Integer) row.get("tokens_out")));
        }
    }

    @Test
    void turnsAndToolCallsRecordLatencyAndTimers() {
        omitUsage.set(false);
        double modelBefore = timerCount("craftpilot.agent.model.latency");
        double toolBefore = timerCount("craftpilot.agent.tool.latency");
        double contextBefore = timerCount("craftpilot.agent.context.build");

        UUID run = runResearch("timed run");

        List<Map<String, Object>> turns = jdbc.sql("""
                SELECT model_latency_ms, context_build_ms FROM agent_turn
                WHERE merchant_id = :m AND run_id = :run ORDER BY turn_no
                """).param("m", merchant).param("run", run).query().listOfRows();
        assertThat(turns).hasSize(2);
        for (Map<String, Object> turn : turns) {
            assertThat((Integer) turn.get("model_latency_ms")).isGreaterThanOrEqualTo((int) STUB_DELAY_MS);
            assertThat((Integer) turn.get("context_build_ms")).isNotNull().isGreaterThanOrEqualTo(0);
        }
        List<Integer> toolLatencies = jdbc.sql("""
                SELECT latency_ms FROM tool_invocation WHERE merchant_id = :m AND run_id = :run
                """).param("m", merchant).param("run", run).query(Integer.class).list();
        assertThat(toolLatencies).hasSize(1).doesNotContainNull();

        assertThat(timerCount("craftpilot.agent.model.latency") - modelBefore).isEqualTo(2.0);
        assertThat(timerCount("craftpilot.agent.tool.latency") - toolBefore).isEqualTo(1.0);
        assertThat(timerCount("craftpilot.agent.context.build") - contextBefore).isEqualTo(2.0);
    }

    private UUID runResearch(String objective) {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange("/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST, new HttpEntity<>(Map.of("revisionIds", List.of(revisionId), "objective", objective),
                        headers), JsonNode.class);
        UUID run = UUID.fromString(created.getBody().path("runId").asText());
        worker.claimAndRun(1);
        assertThat(jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", run).query(String.class).single()).isEqualTo("SUCCEEDED");
        return run;
    }

    private double timerCount(String name) {
        return meters.find(name).timers().stream().mapToDouble(t -> t.count()).sum();
    }

    private static BigDecimal price(int in, int out) {
        BigDecimal dollars = INPUT_PER_MTOK.multiply(BigDecimal.valueOf(in))
                .add(OUTPUT_PER_MTOK.multiply(BigDecimal.valueOf(out)))
                .movePointLeft(6);
        return dollars.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static UUID id(JsonNode body) {
        return UUID.fromString(body.path("id").asText());
    }

    private static JsonNode post(TestRestTemplate rest, String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, headers()), JsonNode.class)
                .getBody();
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("instr-owner", PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static UUID uploadAsset(TestRestTemplate rest) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource("instr-img".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "instr.png";
            }
        });
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String toolCallBody(String revision, String usage) {
        String args = "{\\\"revisionId\\\":\\\"" + revision + "\\\",\\\"query\\\":\\\"fabric\\\",\\\"topK\\\":5}";
        return "{\"id\":\"chatcmpl-tool\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":"
                + "\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"evidence_search\",\"arguments\":\"" + args + "\"}}]}}]" + usage + "}";
    }

    private static String finalBody(String evidence, String usage) {
        String content = "{\\\"recommendation\\\":\\\"PRODUCE\\\",\\\"summary\\\":\\\"ok\\\",\\\"evidenceIds\\\":[\\\""
                + evidence + "\\\"]}";
        return "{\"id\":\"chatcmpl-final\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":"
                + "\"assistant\",\"content\":\"" + content + "\"}}]" + usage + "}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String vectorJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            sb.append(i == 0 ? "" : ",").append("0.5");
        }
        return sb.append(']').toString();
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }
}
