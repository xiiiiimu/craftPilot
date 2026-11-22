package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.runtime.lease.LeaseHeartbeat;
import com.craftpilot.runtime.ledger.BudgetService;
import com.craftpilot.runtime.mcp.McpClient;
import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Batch A regression tests: P0-1 lease heartbeat, P0-2 model reservation sizing, P0-3 reservation release and
 * failure reasons. Timing margins: lease 4s, renew every 500ms, slow model calls take 6s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeBatchATest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-batch-a";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String LOGIN = "batch-a-owner";

    private static final long LEASE_SECONDS = 4;
    private static final long RENEW_INTERVAL_MS = 500;
    private static final long SLOW_CALL_MS = 6000;
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);

    private static HttpServer openaiStub;
    private static HttpServer chatStub;
    private static HttpServer mcpServer;
    private static final AtomicInteger chatCalls = new AtomicInteger();
    private static final AtomicInteger chatInFlight = new AtomicInteger();
    private static final AtomicLong chatDelayMs = new AtomicLong();
    private static final AtomicReference<Function<String, String>> responder = new AtomicReference<>();
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
        // Supervisor workers call the model in parallel; the default executor would serialize them.
        chatStub.setExecutor(Executors.newCachedThreadPool());
        chatStub.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int call = chatCalls.incrementAndGet();
            chatInFlight.incrementAndGet();
            try {
                long delay = chatDelayMs.get();
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                byte[] bytes = responder.get().apply(request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("x-request-id", "req_batch_a_" + UUID.randomUUID());
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                chatInFlight.decrementAndGet();
            }
        });
        chatStub.start();

        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (request.contains("tools/list")) {
                response = """
                        {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"lookup_material","description":"lookup","inputSchema":{"type":"object","required":["materialCode"],"properties":{"materialCode":{"type":"string"}},"additionalProperties":false}}]}}
                        """;
            } else if (request.contains("tools/call")) {
                response = """
                        {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"FAB-9 cotton stock ok"}],"structuredContent":{"materialCode":"FAB-9","status":"ok"},"isError":false}}
                        """;
            } else {
                response = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"unknown\"}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mcpServer.start();

        config.add("CRAFTPILOT_JDBC_URL", RuntimeBatchATest::jdbcUrl);
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
        config.add("craftpilot.runtime.lease-seconds", () -> String.valueOf(LEASE_SECONDS));
        config.add("craftpilot.runtime.lease-renew-interval-ms", () -> String.valueOf(RENEW_INTERVAL_MS));
    }

    @AfterAll
    static void stopStubs() {
        if (openaiStub != null) {
            openaiStub.stop(0);
        }
        if (chatStub != null) {
            chatStub.stop(0);
        }
        if (mcpServer != null) {
            mcpServer.stop(0);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired RunWorker worker;
    @Autowired BudgetService budget;
    @Autowired LeaseHeartbeat heartbeat;

    static UUID merchant;
    static UUID revisionId;
    static UUID surveyId;

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Future<Integer> backgroundRun;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio, @Autowired TestRestTemplate rest,
                         @Autowired McpClient mcp) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        UUID owner = UUID.randomUUID();
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,'Batch A Owner')")
                .param("id", owner).param("login", LOGIN).update();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'Batch A Merchant')").param("id", merchant).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:hash,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("p", owner).param("hash", encoder.encode(PASSWORD)).update();

        UUID groupId = id(post(rest, "/merchants/" + merchant + "/groups", Map.of("name", "BatchARoom")));
        UUID campaign = id(post(rest, "/merchants/" + merchant + "/campaigns", Map.of("name", "BatchACamp")));
        UUID style = id(post(rest, "/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Dress", "category", "wear")));
        UUID asset = uploadAsset(rest);
        revisionId = id(post(rest, "/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v1")));
        surveyId = id(post(rest, "/merchants/" + merchant + "/groups/" + groupId + "/surveys",
                Map.of("revisionIds", List.of(revisionId))));
        post(rest, "/merchants/" + merchant + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢面料 FAB-9",
                "card", Map.of("revisionId", revisionId)));
        UUID evidenceId = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s ORDER BY seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).query(UUID.class).single();
        activeRevision.set(revisionId.toString());
        activeEvidence.set(evidenceId.toString());

        String endpoint = "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp";
        mcp.registerConnector(merchant, "local-materials", endpoint, List.of("lookup_material"), true);
        mcp.discover(merchant, "local-materials");
    }

    @BeforeEach
    void resetStub() {
        // A previous test's interrupted worker may have left a stub call sleeping; it would pollute chatInFlight.
        await("chat stub idle", () -> chatInFlight.get() == 0);
        chatCalls.set(0);
        chatDelayMs.set(0);
        responder.set(RuntimeBatchATest::researchToolsThenFinal);
    }

    @AfterEach
    void cleanup() throws Exception {
        chatDelayMs.set(0);
        if (backgroundRun != null) {
            try {
                backgroundRun.get(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the test itself reports failures; here we only make sure the worker thread is gone
            }
        }
        background.shutdownNow();
        background.awaitTermination(30, TimeUnit.SECONDS);
        // Leave no claimable run behind for the next test.
        jdbc.sql("""
                UPDATE agent_run SET status = 'CANCELLED', lease_until = NULL, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
                """).param("m", merchant).update();
    }

    // ---------------------------------------------------------------- P0-1 lease heartbeat

    @Test
    @Order(1)
    void slowSingleModelCallKeepsLease() {
        responder.set(req -> finalBody(activeEvidence.get()));
        chatDelayMs.set(SLOW_CALL_MS);
        UUID runId = createRun("A1 slow single call", "launch-research-r3");

        startWorker();
        awaitModelCallInFlight(runId);
        OffsetDateTime leaseAtCallStart = leaseUntil(runId);
        await("initial lease window passed", () -> dbNowAfter(leaseAtCallStart.plusNanos(500_000_000L)));

        var stolen = worker.claimOnePublic();
        assertThat(stolen).as("a second worker must not be able to claim a run whose call is in flight")
                .isNull();

        awaitWorkerDone();
        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("SUCCEEDED");
        assertThat(leaseEpoch(runId)).isEqualTo(1L);
        assertNoHeldReservations(runId);
    }

    @Test
    @Order(2)
    void multiTurnRunCompletesWithoutReclaim() {
        responder.set(RuntimeBatchATest::researchToolsThenFinal); // 2 tool turns, then final = 3 turns
        chatDelayMs.set(SLOW_CALL_MS / 2);
        UUID runId = createRun("A2 multi turn", "launch-research-r3");

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("SUCCEEDED");
        assertThat(leaseEpoch(runId)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM agent_turn WHERE merchant_id = :m AND run_id = :r", runId))
                .isEqualTo(3L);
        assertNoHeldReservations(runId);

        // Heartbeat must not keep touching a terminal run.
        OffsetDateTime leaseAfter = leaseUntil(runId);
        OffsetDateTime observedAt = dbNow();
        await("two renew intervals", () -> dbNowAfter(observedAt.plusNanos(RENEW_INTERVAL_MS * 2 * 1_000_000L)));
        assertThat(leaseUntil(runId)).isEqualTo(leaseAfter);
    }

    @Test
    @Order(3)
    void supervisorWorkersShareRunHeartbeat() {
        responder.set(RuntimeBatchATest::supervisorWorkers);
        chatDelayMs.set(SLOW_CALL_MS);
        UUID runId = createRun("A3 supervisor slow workers", "launch-supervise-r4");

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("WAITING_APPROVAL");
        assertThat(leaseEpoch(runId)).isEqualTo(1L);
        assertNoHeldReservations(runId);
    }

    @Test
    @Order(4)
    void lostLeaseStopsWorkerAfterInFlightCall() {
        responder.set(req -> toolCallBody(activeRevision.get()));
        chatDelayMs.set(SLOW_CALL_MS);
        UUID runId = createRun("A4 lease lost mid call", "launch-research-r3");

        startWorker();
        awaitModelCallInFlight(runId);
        // Another worker takes over.
        jdbc.sql("""
                UPDATE agent_run SET lease_epoch = lease_epoch + 1, lease_owner = 'other-worker',
                       lease_until = clock_timestamp() + interval '60 seconds'
                WHERE merchant_id = :m AND id = :r
                """).param("m", merchant).param("r", runId).update();
        OffsetDateTime takeoverLease = leaseUntil(runId);

        awaitWorkerDone();
        assertThat(count("SELECT COUNT(*) FROM tool_invocation WHERE merchant_id = :m AND run_id = :r", runId))
                .as("stale worker must not dispatch tools").isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM agent_turn WHERE merchant_id = :m AND run_id = :r
                  AND status = 'RESPONSE_COMMITTED'
                """, runId)).as("stale worker must not commit the turn").isZero();
        assertThat(leaseUntil(runId)).as("stale heartbeat must not extend the new owner's lease")
                .isEqualTo(takeoverLease);
        assertNoHeldReservations(runId);
    }

    @Test
    @Order(5)
    void heartbeatStopsAtExecutionDeadline() {
        responder.set(req -> toolCallBody(activeRevision.get()));
        chatDelayMs.set(SLOW_CALL_MS);
        UUID runId = createRun("A5 deadline", "launch-research-r3");
        jdbc.sql("""
                UPDATE agent_run SET execution_deadline = clock_timestamp() + interval '5 seconds'
                WHERE merchant_id = :m AND id = :r
                """).param("m", merchant).param("r", runId).update();

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("FAILED");
        assertThat(terminalReason(runId)).as("%s", runState(runId)).isEqualTo("DEADLINE");
        assertThat(leaseEpoch(runId)).as("deadline must not cause a reclaim").isEqualTo(1L);
        OffsetDateTime deadline = jdbc.sql("""
                SELECT execution_deadline FROM agent_run WHERE merchant_id = :m AND id = :r
                """).param("m", merchant).param("r", runId).query(OffsetDateTime.class).single();
        assertThat(leaseUntil(runId)).as("no renewal after the deadline")
                .isBeforeOrEqualTo(deadline.plusSeconds(LEASE_SECONDS));
        assertNoHeldReservations(runId);
    }

    // ---------------------------------------------------------------- P0-2 reservation sizing

    @Test
    @Order(6)
    void reservationTracksContextSize() {
        UUID runId = createRun("B2 reservation growth", "launch-research-r3");

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("SUCCEEDED");
        List<Integer> reserved = jdbc.sql("""
                SELECT tokens_reserved FROM budget_reservation
                WHERE merchant_id = :m AND run_id = :r AND purpose = 'MODEL'
                ORDER BY created_at
                """).param("m", merchant).param("r", runId).query(Integer.class).list();
        assertThat(reserved).hasSize(3);
        assertThat(reserved).allSatisfy(t -> assertThat(t).as("context estimate + max_tokens(800)")
                .isGreaterThan(800));
        assertThat(reserved).isSorted();
        assertThat(reserved.get(2)).isGreaterThan(reserved.get(0));
        assertNoHeldReservations(runId);
    }

    @Test
    @Order(7)
    void tokenBudgetBlocksBeforeModelCall() {
        UUID runId = createRun("B3 token budget", "launch-research-r3");
        jdbc.sql("UPDATE agent_run SET token_budget = 900 WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).update();

        startWorker();
        awaitWorkerDone();

        assertThat(chatCalls.get()).as("reservation must block before spending").isZero();
        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("FAILED");
        assertThat(terminalReason(runId)).as("%s", runState(runId)).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertNoHeldReservations(runId);
    }

    // ---------------------------------------------------------------- P0-3 release and failure reasons

    @Test
    @Order(8)
    void maxTurnsRecordsBusinessCode() {
        responder.set(req -> toolCallBody(activeRevision.get()));
        UUID runId = createRun("C1 max turns", "launch-research-r3");
        jdbc.sql("UPDATE agent_run SET token_budget = 1000000 WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).update();

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("FAILED");
        assertThat(terminalReason(runId)).as("%s", runState(runId)).isEqualTo("MAX_TURNS_EXCEEDED");
        assertThat(jdbc.sql("""
                SELECT t.status FROM ticket t JOIN agent_run r ON r.merchant_id = t.merchant_id AND r.ticket_id = t.id
                WHERE r.merchant_id = :m AND r.id = :r
                """).param("m", merchant).param("r", runId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT turns_used FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(Integer.class).single()).isEqualTo(8);
        assertThat(chatCalls.get()).isEqualTo(8);
        assertNoHeldReservations(runId);
    }

    @Test
    @Order(9)
    void supervisorBudgetFailureKeepsCodeAndReleases() {
        responder.set(RuntimeBatchATest::supervisorWorkers);
        UUID runId = createRun("C3 supervisor max turns", "launch-supervise-r4");
        jdbc.sql("UPDATE agent_run SET max_turns = 1 WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).update();

        startWorker();
        awaitWorkerDone();

        assertThat(status(runId)).as("%s", runState(runId)).isEqualTo("FAILED");
        assertThat(terminalReason(runId)).as("%s", runState(runId)).isEqualTo("SUPERVISOR_MAX_TURNS_EXCEEDED");
        // An interrupted sibling worker may still be finishing its in-flight call.
        await("no HELD reservation", () -> heldReservations(runId) == 0L);
    }

    @Test
    @Order(10)
    void settleWithDuplicateProviderRequestIdStillSettles() {
        UUID runId = createRun("D1 duplicate provider id", "launch-research-r3");
        String providerId = "req_dup_" + UUID.randomUUID();

        UUID first = budget.reserveModel(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01));
        UUID second = budget.reserveModel(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01));
        budget.settleModel(merchant, first, runId, modelCallAttempt(), 20, 15,
                BigDecimal.valueOf(0.00002), "EXACT", providerId);
        // Same provider request settled again (replay): must stay idempotent on the ledger, not abort the settle.
        budget.settleModel(merchant, second, runId, modelCallAttempt(), 20, 15,
                BigDecimal.valueOf(0.00002), "EXACT", providerId);

        assertThat(jdbc.sql("""
                SELECT status FROM budget_reservation WHERE merchant_id = :m AND id IN (:a, :b)
                """).param("m", merchant).param("a", first).param("b", second).query(String.class).list())
                .hasSize(2).containsOnly("SETTLED");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM usage_ledger WHERE merchant_id = :m AND provider_request_id = :p
                """).param("m", merchant).param("p", providerId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT tokens_used FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(Integer.class).single())
                .as("reserved 2x500, actual 2x35").isEqualTo(70);
    }

    @Test
    @Order(11)
    void reserveTurnIsAtomic() {
        UUID runId = createRun("C2 reserveTurn atomic", "launch-research-r3");

        // The last allowed turn must still be reservable (consumeTurn runs first).
        jdbc.sql("UPDATE agent_run SET turns_used = max_turns - 1 WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).update();
        UUID last = budget.reserveTurn(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01));
        assertThat(last).isNotNull();
        budget.release(merchant, last, runId);

        // turns_used == max_turns: refused, nothing reserved, turns_used unchanged.
        int turnsBefore = turnsUsed(runId);
        long reservationsBefore = reservations(runId);
        assertThatThrownBy(() -> budget.reserveTurn(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01)))
                .isInstanceOfSatisfying(ApiFailure.class, ex -> assertThat(ex.code()).isEqualTo("MAX_TURNS_EXCEEDED"));
        assertThat(turnsUsed(runId)).isEqualTo(turnsBefore);
        assertThat(reservations(runId)).isEqualTo(reservationsBefore);

        // Budget refusal rolls back the consumed turn.
        jdbc.sql("UPDATE agent_run SET turns_used = 0, token_budget = 100 WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).update();
        assertThatThrownBy(() -> budget.reserveTurn(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01)))
                .isInstanceOfSatisfying(ApiFailure.class, ex -> assertThat(ex.code()).isEqualTo("TOKEN_BUDGET_EXCEEDED"));
        assertThat(turnsUsed(runId)).isZero();
        assertThat(reservations(runId)).isEqualTo(reservationsBefore);
    }

    // ---------------------------------------------------------------- helpers

    private int turnsUsed(UUID runId) {
        return jdbc.sql("SELECT turns_used FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(Integer.class).single();
    }

    private long reservations(UUID runId) {
        return count("SELECT COUNT(*) FROM budget_reservation WHERE merchant_id = :m AND run_id = :r", runId);
    }

    /** Run summary attached to assertion descriptions; only printed when an assertion fails. */
    private String runState(UUID runId) {
        String run = jdbc.sql("""
                SELECT status || ' reason=' || COALESCE(terminal_reason, '-') || ' epoch=' || lease_epoch
                       || ' turnsUsed=' || turns_used || ' tokensUsed=' || tokens_used
                FROM agent_run WHERE merchant_id = :m AND id = :r
                """).param("m", merchant).param("r", runId).query(String.class).single();
        List<String> turns = jdbc.sql("""
                SELECT turn_no || ':' || status FROM agent_turn WHERE merchant_id = :m AND run_id = :r ORDER BY turn_no
                """).param("m", merchant).param("r", runId).query(String.class).list();
        List<String> reservations = jdbc.sql("""
                SELECT status || ':' || tokens_reserved FROM budget_reservation
                WHERE merchant_id = :m AND run_id = :r ORDER BY created_at
                """).param("m", merchant).param("r", runId).query(String.class).list();
        return "run[" + run + "] turns" + turns + " reservations" + reservations + " chatCalls=" + chatCalls.get();
    }

    private UUID modelCallAttempt() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO model_call_attempt (merchant_id, id, provider, model_name, purpose, request_hash, status)
                VALUES (:m, :id, 'openai-compatible', 'gpt-4o-mini', 'agent_chat', :hash, 'SUCCEEDED')
                """).param("m", merchant).param("id", id).param("hash", "sha256:" + id).update();
        return id;
    }

    private void startWorker() {
        backgroundRun = background.submit(() -> worker.claimAndRun(1));
    }

    private void awaitWorkerDone() {
        try {
            assertThat(backgroundRun.get(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(1);
        } catch (Exception ex) {
            throw new AssertionError("worker did not finish", ex);
        }
        assertThat(heartbeat.activeCount()).as("heartbeat registrations after the run left the worker").isZero();
    }

    /** The model call in flight belongs to this run: its turn row exists and the stub is serving a request. */
    private void awaitModelCallInFlight(UUID runId) {
        await("model call in flight for run " + runId, () -> chatInFlight.get() > 0 && count("""
                SELECT COUNT(*) FROM agent_turn WHERE merchant_id = :m AND run_id = :r AND status = 'REQUESTED'
                """, runId) > 0);
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private UUID createRun(String objective, String workflowId) {
        HttpHeaders headers = authHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> created = rest.exchange(
                "/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("revisionIds", List.of(revisionId), "objective", objective,
                        "workflowId", workflowId), headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(created.getBody().path("runId").asText());
    }

    private String status(UUID runId) {
        return jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(String.class).single();
    }

    private String terminalReason(UUID runId) {
        return jdbc.sql("SELECT COALESCE(terminal_reason, '') FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(String.class).single();
    }

    private long leaseEpoch(UUID runId) {
        return jdbc.sql("SELECT lease_epoch FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(Long.class).single();
    }

    private OffsetDateTime leaseUntil(UUID runId) {
        return jdbc.sql("SELECT lease_until FROM agent_run WHERE merchant_id = :m AND id = :r")
                .param("m", merchant).param("r", runId).query(OffsetDateTime.class).single();
    }

    private OffsetDateTime dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
    }

    private boolean dbNowAfter(OffsetDateTime instant) {
        return dbNow().isAfter(instant);
    }

    private long count(String sql, UUID runId) {
        return jdbc.sql(sql).param("m", merchant).param("r", runId).query(Long.class).single();
    }

    private long heldReservations(UUID runId) {
        return count("""
                SELECT COUNT(*) FROM budget_reservation WHERE merchant_id = :m AND run_id = :r AND status = 'HELD'
                """, runId);
    }

    private void assertNoHeldReservations(UUID runId) {
        List<String> rows = jdbc.sql("""
                SELECT status || ':' || tokens_reserved || ':' || COALESCE(model_call_id::text, '-')
                FROM budget_reservation WHERE merchant_id = :m AND run_id = :r ORDER BY created_at
                """).param("m", merchant).param("r", runId).query(String.class).list();
        assertThat(heldReservations(runId)).as("HELD reservations, rows=%s", rows).isZero();
    }

    private static HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(LOGIN, PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static ResponseEntity<JsonNode> post(TestRestTemplate rest, String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()),
                JsonNode.class);
    }

    private static UUID id(ResponseEntity<JsonNode> response) {
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private static UUID uploadAsset(TestRestTemplate rest) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource("batch-a-img".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "batch-a.png";
            }
        });
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    /** Research agent: one tool call per turn until two tool results are in context, then the final answer. */
    private static String researchToolsThenFinal(String request) {
        int toolMessages = countOccurrences(request, "\"role\":\"tool\"");
        return toolMessages < 2 ? toolCallBody(activeRevision.get()) : finalBody(activeEvidence.get());
    }

    private static String supervisorWorkers(String request) {
        if (request.contains("\"role\":\"tool\"")) {
            return finalWorker(activeEvidence.get());
        }
        if (request.contains("[role=EVIDENCE_WORKER]")) {
            return toolCall("evidence_search", "{\"revisionId\":\"" + activeRevision.get()
                    + "\",\"query\":\"fabric FAB-9\",\"topK\":5}");
        }
        if (request.contains("[role=MATERIAL_WORKER]")) {
            return toolCall("mcp_material_lookup", "{\"materialCode\":\"FAB-9\",\"connectorId\":\"local-materials\"}");
        }
        return finalWorker(activeEvidence.get());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String toolCallBody(String revisionId) {
        return toolCall("evidence_search", "{\"revisionId\":\"" + revisionId + "\",\"query\":\"fabric FAB-9\",\"topK\":5}");
    }

    private static String toolCall(String name, String argsJson) {
        String argsEscaped = argsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-tool\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_" + UUID.randomUUID() + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + argsEscaped
                + "\"}}]}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":15}}";
    }

    private static String finalBody(String evidenceId) {
        String content = "{\"recommendation\":\"REVISE\",\"summary\":\"Fabric feedback supports revise\",\"evidenceIds\":[\""
                + evidenceId + "\"]}";
        String contentEscaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-final\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + contentEscaped + "\"}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":40}}";
    }

    private static String finalWorker(String evidenceId) {
        String content = "{\"summary\":\"worker done\",\"evidenceIds\":[\"" + evidenceId
                + "\"],\"materialNotes\":\"FAB-9 ok\"}";
        String contentEscaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-final\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + contentEscaped + "\"}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":40}}";
    }

    private static String vectorJson(float seed) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(seed);
        }
        return sb.append(']').toString();
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }
}
