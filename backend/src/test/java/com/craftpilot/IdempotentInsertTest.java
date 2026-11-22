package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.craftpilot.events.GroupRealtimeHub;
import com.craftpilot.events.OutboxDispatcher;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Idempotent inserts must not swallow a unique violation inside a transaction: PostgreSQL aborts the
 * transaction, later statements fail and the commit silently becomes a rollback.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class IdempotentInsertTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();

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
    static void configuration(DynamicPropertyRegistry config) {
        config.add("CRAFTPILOT_JDBC_URL", IdempotentInsertTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", () -> REDIS.getMappedPort(6379).toString());
        config.add("CRAFTPILOT_MINIO_ENDPOINT", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> "craftpilot-idem");
        config.add("craftpilot.outbox.dispatch-enabled", () -> "false");
        config.add("craftpilot.runtime.worker-enabled", () -> "false");
    }

    @Autowired JdbcClient jdbc;
    @Autowired CheckpointService checkpoints;
    @Autowired OutboxDispatcher dispatcher;
    @MockitoSpyBean GroupRealtimeHub hub;

    /** Reproduces CheckpointService.java:40: a replayed transition key must return the existing seq. */
    @Test
    void checkpointAppendReplayReturnsExistingSeq() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID survey = UUID.randomUUID();
        UUID run = insertBareRun(merchant, survey);

        long first = checkpoints.append(merchant, survey, run, "turn-1-requested", "research", "REQUESTED",
                Map.of("turnNo", 1));
        long replay = checkpoints.append(merchant, survey, run, "turn-1-requested", "research", "REQUESTED",
                Map.of("turnNo", 1));

        assertThat(replay).isEqualTo(first).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT checkpoint_head FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", run).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM checkpoint WHERE merchant_id = :m AND run_id = :run")
                .param("m", merchant).param("run", run).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM runtime_event
                WHERE merchant_id = :m AND run_id = :run AND event_type = 'CHECKPOINT'
                """).param("m", merchant).param("run", run).query(Long.class).single()).isEqualTo(1L);

        long next = checkpoints.append(merchant, survey, run, "turn-1-committed", "research",
                "RESPONSE_COMMITTED", Map.of());
        assertThat(next).isEqualTo(2L);
    }

    /**
     * Regression guard for OutboxDispatcher.java:162, not a reproduction: the receipt insert runs in its own
     * transaction, so the swallowed duplicate key had no observable effect and this test also passes on the
     * old code. It pins the behaviour when another worker records the receipt while we are broadcasting.
     */
    @Test
    void outboxDeliveryToleratesReceiptWrittenByConcurrentWorker() {
        UUID merchant = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outbox (merchant_id, id, aggregate_type, aggregate_id, event_type, payload_json, status)
                VALUES (:m, :id, 'group', :g, 'MESSAGE_PERSISTED', CAST(:payload AS jsonb), 'PENDING')
                """).param("m", merchant).param("id", eventId).param("g", groupId)
                .param("payload", "{\"groupId\":\"" + groupId + "\",\"groupSeq\":1,\"messageId\":\""
                        + UUID.randomUUID() + "\"}")
                .update();
        doAnswer(invocation -> {
            jdbc.sql("""
                    INSERT INTO consumer_receipt (merchant_id, consumer_name, event_id, effect_key)
                    VALUES (:m, 'ws-fanout', :id, :effect)
                    """).param("m", merchant).param("id", eventId)
                    .param("effect", "MESSAGE_PERSISTED:" + eventId).update();
            return invocation.callRealMethod();
        }).when(hub).broadcast(any(), any(), any());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("""
                SELECT status, attempt_count, last_error FROM outbox WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", eventId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error")).isNull();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM consumer_receipt WHERE merchant_id = :m AND event_id = :id
                """).param("m", merchant).param("id", eventId).query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * Inserts an agent_run without its ticket/attempt graph. FK triggers are disabled for this one superuser
     * session only; checkpoint and runtime_event rows written by the service still check their FK to agent_run.
     */
    private static UUID insertBareRun(UUID merchant, UUID survey) throws Exception {
        UUID run = UUID.randomUUID();
        try (Connection con = DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = con.createStatement()) {
                st.execute("SET session_replication_role = replica");
            }
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO agent_run (merchant_id, id, survey_id, ticket_id, attempt_id, workflow_id,
                        workflow_version, agent_spec_id, model_profile_id, status, execution_deadline)
                    VALUES (?, ?, ?, ?, ?, 'launch-research-r3', '1', 'research-agent-r3', 'chat-default-r3',
                        'RUNNING', clock_timestamp() + interval '1 hour')
                    """)) {
                ps.setObject(1, merchant);
                ps.setObject(2, run);
                ps.setObject(3, survey);
                ps.setObject(4, UUID.randomUUID());
                ps.setObject(5, UUID.randomUUID());
                ps.executeUpdate();
            }
        }
        return run;
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }
}
