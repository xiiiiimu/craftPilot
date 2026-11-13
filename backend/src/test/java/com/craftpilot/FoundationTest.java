package com.craftpilot;

import static org.assertj.core.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class FoundationTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-integration";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16-trixie@sha256:9cf8b511f0e9a2c209b61430fc139f1ab9c75edbb31fb1751db6617b246a897a")
                    .asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:7.4.12-alpine3.22@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe").withExposedPorts(6379);
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:786cb11d340b1a657de8dc899560e36e1d1fb371d3d6f3eb6c903a0bb0cec714")
            .withEnv("MINIO_ROOT_USER", MINIO_USER).withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry config) {
        config.add("CRAFTPILOT_JDBC_URL", () -> jdbcUrl());
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", FoundationTest::minioEndpoint);
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> true);
        config.add("craftpilot.outbox.dispatch-enabled", () -> false);
    }

    @Autowired JdbcClient jdbc;
    @Autowired TestRestTemplate http;
    @Autowired PasswordEncoder encoder;

    private static final String password = UUID.randomUUID().toString();
    private static final UUID a = UUID.randomUUID();
    private static final UUID b = UUID.randomUUID();
    private static UUID revokeTarget;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder, @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        var hash = encoder.encode(password);
        var owner = user(jdbc, "owner", hash, false);
        var member = user(jdbc, "member", hash, false);
        var dual = user(jdbc, "dual", hash, false);
        var bOwner = user(jdbc, "other-owner", hash, false);
        user(jdbc, "unaffiliated", hash, false);
        user(jdbc, "expired", hash, true);
        revokeTarget = user(jdbc, "revocable", hash, false);
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:a,'A'),(:b,'B')").param("a", a).param("b", b).update();
        addMembership(jdbc, a, owner, "OWNER");
        addMembership(jdbc, a, member, "MEMBER");
        addMembership(jdbc, a, dual, "MEMBER");
        addMembership(jdbc, b, dual, "OWNER");
        addMembership(jdbc, b, bOwner, "OWNER");
        addMembership(jdbc, a, revokeTarget, "MEMBER");
        addMembership(jdbc, b, revokeTarget, "MEMBER");
    }

    @Test
    void flywayMigrateAndConstraints() {
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_app").defaultSchema("craftpilot_app").load();
        assertThat(jdbc.sql("""
                SELECT version FROM flyway_schema_history WHERE type = 'SQL' AND success ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2", "3", "4", "5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.sql("""
                SELECT indexname FROM pg_indexes WHERE schemaname='craftpilot_app'
                """).query(String.class).list()).contains("merchant_member_pkey", "merchant_member_principal_active_idx");
        assertThatThrownBy(() -> addMembership(jdbc, a, UUID.randomUUID(), "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID principal = user(jdbc, "constraint-probe", encoder.encode(password), false);
        assertThatThrownBy(() -> addMembership(jdbc, UUID.randomUUID(), principal, "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> addMembership(jdbc, a, principal, "GROUP_ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        addMembership(jdbc, a, principal, "MEMBER");
        UUID otherOwner = jdbc.sql("SELECT id FROM principal WHERE login = 'other-owner'").query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO membership_revoke_command (merchant_id, actor_id, command_key, target_id)
                VALUES (:merchant, :actor, :key, :target)
                """).param("merchant", b).param("actor", otherOwner).param("key", UUID.randomUUID())
                .param("target", principal).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> addMembership(jdbc, a, principal, "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authenticationRejectsForgeryInvalidAndExpiredCredentials(CapturedOutput output) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", UUID.randomUUID().toString());
        headers.set("X-Merchant-Id", a.toString());
        headers.set("X-Role", "OWNER");
        var unauthenticated = http.exchange("/api/v1/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertError(unauthenticated, 401, "UNAUTHENTICATED");
        assertError(http.withBasicAuth("owner", "invalid").getForEntity("/api/v1/me", JsonNode.class),
                401, "UNAUTHENTICATED");
        assertError(get("expired", "/api/v1/me"), 401, "UNAUTHENTICATED");
        assertThat(get("owner", "/api/v1/me").getBody().path("login").asText()).isEqualTo("owner");
        assertThat(output.getAll()).doesNotContain(password, MINIO_PASSWORD,
                java.util.Base64.getEncoder().encodeToString(("owner:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void isolationAndRoleAreEvaluatedPerMerchant() {
        assertError(get("member", "/api/v1/merchants/" + b), 404, "NOT_FOUND");
        assertError(get("member", "/api/v1/merchants/" + UUID.randomUUID()), 404, "NOT_FOUND");
        assertError(get("unaffiliated", "/api/v1/merchants/" + a), 404, "NOT_FOUND");
        assertThat(get("unaffiliated", "/api/v1/me/merchants").getBody().path("items").size()).isZero();
        assertThat(get("dual", "/api/v1/me/merchants").getBody().path("items").size()).isEqualTo(2);
        assertError(get("dual", "/api/v1/merchants/" + a + "/members"), 403, "FORBIDDEN");
        assertThat(get("dual", "/api/v1/merchants/" + b + "/members").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("owner", "/api/v1/merchants/" + a + "/members").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(revoke("member", a), 403, "FORBIDDEN");
        assertError(revoke("owner", b), 404, "NOT_FOUND");
    }

    @Test
    void revocationTakesEffectForSameCredentialsAndPreservesOtherMembership() {
        assertThat(get("revocable", "/api/v1/merchants/" + a).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID commandKey = UUID.randomUUID();
        assertThat(revoke("owner", a, revokeTarget, commandKey).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(revoke("owner", a, revokeTarget, commandKey).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertError(get("revocable", "/api/v1/merchants/" + a), 404, "NOT_FOUND");
        assertThat(get("revocable", "/api/v1/merchants/" + b).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID owner = jdbc.sql("SELECT id FROM principal WHERE login = 'owner'").query(UUID.class).single();
        assertError(revoke("owner", a, owner, commandKey), 409, "IDEMPOTENCY_CONFLICT");
        assertError(revoke("owner", a, owner, UUID.randomUUID()), 409, "OWNER_MEMBERSHIP_PROTECTED");
        assertError(http.withBasicAuth("owner", password).exchange(
                "/api/v1/merchants/" + a + "/members/" + revokeTarget, HttpMethod.DELETE, null, JsonNode.class),
                422, "INVALID_REQUEST");
    }

    @Test
    void validatesRequestsAndCorrelatesErrorTrace() {
        var headers = new HttpHeaders();
        var trace = UUID.randomUUID().toString();
        headers.setBasicAuth("owner", password);
        headers.set("X-Trace-Id", trace);
        var result = http.exchange("/api/v1/me/merchants?limit=101", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertError(result, 422, "INVALID_REQUEST");
        assertThat(result.getBody().path("traceId").asText()).isEqualTo(trace);
        assertError(get("owner", "/api/v1/merchants/not-a-uuid"), 422, "INVALID_REQUEST");
    }

    @Test
    void realDependenciesAndDependencyOutage() throws Exception {
        var ready = http.getForEntity("/actuator/health/readiness", JsonNode.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String component : new String[]{"db", "redis", "minio"}) {
            assertThat(ready.getBody().path("components").path(component).path("status").asText()).isEqualTo("UP");
        }
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            Thread.sleep(Duration.ofSeconds(6));
            var down = http.getForEntity("/actuator/health/readiness", JsonNode.class);
            assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(down.getBody().path("components").path("redis").path("status").asText()).isEqualTo("DOWN");
            assertThat(http.getForEntity("/actuator/health/liveness", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    void refusesDevelopmentAuthenticationInProductionProfile() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CraftPilotApplication.class)
                .profiles("prod").run(
                        "--server.port=0", "--craftpilot.auth.dev-enabled=true",
                        "--spring.datasource.url=" + jdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                        "--craftpilot.minio.endpoint=" + minioEndpoint(),
                        "--craftpilot.minio.access-key=" + MINIO_USER,
                        "--craftpilot.minio.secret-key=" + MINIO_PASSWORD,
                        "--craftpilot.minio.bucket=" + BUCKET))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Development authentication requires the exclusive dev profile");
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static UUID user(JdbcClient jdbc, String login, String hash, boolean expired) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,:login)")
                .param("id", id).param("login", login).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:id,:hash,clock_timestamp()-interval '2 days',
                  clock_timestamp() + CASE WHEN :expired THEN interval '-1 hour' ELSE interval '1 hour' END)
                """).param("id", id).param("hash", hash).param("expired", expired).update();
        return id;
    }

    private static void addMembership(JdbcClient jdbc, UUID merchant, UUID principal, String role) {
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,:r)")
                .param("m", merchant).param("p", principal).param("r", role).update();
    }

    private ResponseEntity<JsonNode> get(String login, String path) {
        return http.withBasicAuth(login, password).getForEntity(path, JsonNode.class);
    }

    private ResponseEntity<JsonNode> revoke(String login, UUID merchant) {
        return revoke(login, merchant, revokeTarget, UUID.randomUUID());
    }

    private ResponseEntity<JsonNode> revoke(String login, UUID merchant, UUID target, UUID commandKey) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", commandKey.toString());
        return http.withBasicAuth(login, password).exchange("/api/v1/merchants/" + merchant + "/members/" + target,
                HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);
    }

    private void assertError(ResponseEntity<JsonNode> result, int status, String code) {
        assertThat(result.getStatusCode().value()).isEqualTo(status);
        assertThat(result.getBody().path("code").asText()).isEqualTo(code);
        assertThat(result.getBody().path("traceId").asText()).isEqualTo(result.getHeaders().getFirst("X-Trace-Id"));
        assertThat(result.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }
}
