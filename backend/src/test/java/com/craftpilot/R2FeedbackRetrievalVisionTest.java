package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
class R2FeedbackRetrievalVisionTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r2";
    private static final String PASSWORD = UUID.randomUUID().toString();

    private static HttpServer openaiStub;
    private static HttpServer qwenStub;
    private static final AtomicInteger openaiMode = new AtomicInteger(0); // 0 ok, 1=429, 2=wrong dim, 3=500
    private static final AtomicInteger qwenMode = new AtomicInteger(0); // 0 ok, 1=503, 2=fabricated evidence

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
            int mode = openaiMode.get();
            byte[] body;
            int status;
            if (mode == 1) {
                status = 429;
                body = "{\"error\":\"rate\"}".getBytes(StandardCharsets.UTF_8);
            } else if (mode == 2) {
                status = 200;
                body = ("{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"total_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (mode == 3) {
                status = 500;
                body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                float seed = req.contains("FAB-9") ? 0.91f : (req.contains("fabric") ? 0.72f : 0.11f);
                body = ("{\"data\":[{\"embedding\":" + vectorJson(seed) + "}],\"usage\":{\"total_tokens\":12}}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        openaiStub.start();

        qwenStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        qwenStub.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body;
            int status = 200;
            int mode = qwenMode.get();
            if (mode == 1) {
                status = 503;
                body = "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
            } else if (mode == 2) {
                body = ("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"visibleDifferences\\\":[],\\\"uncertainty\\\":\\\"evidence_id:"
                        + "11111111-1111-1111-1111-111111111111\\\"}\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                body = ("{\"id\":\"chatcmpl-ok\",\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"visibleDifferences\\\":[{\\\"aspect\\\":\\\"collar\\\",\\\"observation\\\":\\\"V2 collar is taller\\\",\\\"certainty\\\":\\\"HIGH\\\"}],"
                        + "\\\"uncertainty\\\":\\\"Fabric weight cannot be confirmed from images\\\"}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30}}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        qwenStub.start();

        config.add("CRAFTPILOT_JDBC_URL", R2FeedbackRetrievalVisionTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", R2FeedbackRetrievalVisionTest::minioEndpoint);
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> true);
        config.add("craftpilot.outbox.dispatch-enabled", () -> false);
        config.add("craftpilot.openai.base-url", () -> "http://127.0.0.1:" + openaiStub.getAddress().getPort());
        config.add("craftpilot.openai.api-key", () -> "test-openai-key");
        config.add("craftpilot.qwen.base-url",
                () -> "http://127.0.0.1:" + qwenStub.getAddress().getPort());
        config.add("craftpilot.qwen.api-key", () -> "test-qwen-key");
    }

    @AfterAll
    static void stopStubs() {
        if (openaiStub != null) {
            openaiStub.stop(0);
        }
        if (qwenStub != null) {
            qwenStub.stop(0);
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;

    private static UUID merchantA;
    private static UUID merchantB;
    private static UUID ownerA;
    private static UUID memberA;
    private static UUID memberA2;
    private static UUID ownerB;
    private static UUID groupId;
    private static UUID styleId;
    private static UUID revisionV1;
    private static UUID revisionV2;
    private static UUID surveyId;
    private static UUID attributedMessageId;
    private static UUID evidenceId;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        String hash = encoder.encode(PASSWORD);
        ownerA = user(jdbc, "r2-owner-a", hash);
        memberA = user(jdbc, "r2-member-a", hash);
        memberA2 = user(jdbc, "r2-member-a2", hash);
        ownerB = user(jdbc, "r2-owner-b", hash);
        merchantA = UUID.randomUUID();
        merchantB = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:a,'R2-A'),(:b,'R2-B')")
                .param("a", merchantA).param("b", merchantB).update();
        addMembership(jdbc, merchantA, ownerA, "OWNER");
        addMembership(jdbc, merchantA, memberA, "MEMBER");
        addMembership(jdbc, merchantA, memberA2, "MEMBER");
        addMembership(jdbc, merchantB, ownerB, "OWNER");
    }

    @Test
    @Order(1)
    void cardAttributionOpinionEvidenceAndIdempotency() {
        groupId = UUID.fromString(post("/merchants/" + merchantA + "/groups", "r2-owner-a",
                Map.of("name", "R2Room"), JsonNode.class).getBody().path("id").asText());
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r2-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA2, "r2-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);

        UUID campaignId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns", "r2-owner-a",
                Map.of("name", "R2Camp"), JsonNode.class).getBody().path("id").asText());
        styleId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns/" + campaignId + "/styles",
                "r2-owner-a", Map.of("name", "Coat", "category", "outer"), JsonNode.class).getBody().path("id").asText());
        UUID asset1 = uploadAsset("r2-owner-a", merchantA, "image/png", "v1-img".getBytes(StandardCharsets.UTF_8));
        UUID asset2 = uploadAsset("r2-owner-a", merchantA, "image/png", "v2-img".getBytes(StandardCharsets.UTF_8));
        revisionV1 = UUID.fromString(post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions", "r2-owner-a",
                Map.of("assetIds", List.of(asset1), "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)), "notes", "v1"), JsonNode.class)
                .getBody().path("id").asText());
        revisionV2 = UUID.fromString(post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions", "r2-owner-a",
                Map.of("parentRevisionId", revisionV1, "assetIds", List.of(asset2), "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)), "notes", "v2"), JsonNode.class)
                .getBody().path("id").asText());

        surveyId = UUID.fromString(post("/merchants/" + merchantA + "/groups/" + groupId + "/surveys", "r2-owner-a",
                Map.of("revisionIds", List.of(revisionV1, revisionV2)), JsonNode.class).getBody().path("id").asText());

        var cardMsg = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-owner-a", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢这版，面料不错 FAB-9",
                "card", Map.of("revisionId", revisionV2)), JsonNode.class);
        assertThat(cardMsg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        attributedMessageId = UUID.fromString(cardMsg.getBody().path("id").asText());

        var attr = jdbc.sql("""
                SELECT status, reason_code, revision_id FROM message_attribution
                WHERE merchant_id = :m AND message_id = :msg
                """).param("m", merchantA).param("msg", attributedMessageId)
                .query((rs, row) -> Map.of(
                        "status", rs.getString("status"),
                        "reason", rs.getString("reason_code"),
                        "revision", rs.getObject("revision_id", UUID.class)))
                .single();
        assertThat(attr.get("status")).isEqualTo("ATTRIBUTED");
        assertThat(attr.get("reason")).isEqualTo("CARD_REVISION");
        assertThat(attr.get("revision")).isEqualTo(revisionV2);

        var again = post("/merchants/" + merchantA + "/messages/" + attributedMessageId + "/attribution",
                "r2-owner-a", Map.of(), JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM message_attribution WHERE message_id = :id")
                .param("id", attributedMessageId).query(Long.class).single()).isEqualTo(1L);

        var issues = auth("r2-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/issues?revisionId=" + revisionV2,
                JsonNode.class);
        assertThat(issues.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.getBody().size()).isGreaterThan(0);
        assertThat(issues.getBody().get(0).path("independentAuthors").asInt()).isGreaterThanOrEqualTo(1);

        evidenceId = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s AND revision_id = :r
                ORDER BY seq LIMIT 1
                """).param("m", merchantA).param("s", surveyId).param("r", revisionV2).query(UUID.class).single();
        var evidence = auth("r2-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/evidence/" + evidenceId, JsonNode.class);
        assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evidence.getBody().path("visibility").asText()).isEqualTo("ACTIVE");

        var verify = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/evidence/verify", "r2-owner-a",
                Map.of("evidenceIds", List.of(evidenceId, UUID.randomUUID())), JsonNode.class);
        assertThat(verify.getBody().path("validIds").size()).isEqualTo(1);
        assertThat(verify.getBody().path("invalidIds").size()).isEqualTo(1);

        var crossTenant = auth("r2-owner-b").getForEntity(
                "/api/v1/merchants/" + merchantA + "/evidence/" + evidenceId, JsonNode.class);
        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(2)
    void replyChainAmbiguityOutOfWindowAndAuthorDedup() {
        var parent = jdbc.sql("""
                SELECT id FROM message_attribution WHERE message_id = :id
                """).param("id", attributedMessageId).query(UUID.class).single();
        assertThat(parent).isNotNull();

        var reply = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-member-a", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "同意，面料还可以",
                "replyToId", attributedMessageId), JsonNode.class);
        UUID replyId = UUID.fromString(reply.getBody().path("id").asText());
        assertThat(jdbc.sql("SELECT status, reason_code, revision_id FROM message_attribution WHERE message_id = :id")
                .param("id", replyId)
                .query((rs, row) -> rs.getString("status") + "|" + rs.getString("reason_code") + "|"
                        + rs.getObject("revision_id", UUID.class))
                .single()).isEqualTo("ATTRIBUTED|REPLY_CHAIN|" + revisionV2);

        var ambiguous = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-member-a2", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "这个颜色一般"), JsonNode.class);
        UUID ambId = UUID.fromString(ambiguous.getBody().path("id").asText());
        assertThat(jdbc.sql("SELECT status, reason_code FROM message_attribution WHERE message_id = :id")
                .param("id", ambId)
                .query((rs, row) -> rs.getString("status") + "|" + rs.getString("reason_code"))
                .single()).isEqualTo("AMBIGUOUS|MULTI_REVISION_NO_CARD");

        UUID attrId = jdbc.sql("SELECT id FROM message_attribution WHERE message_id = :id")
                .param("id", ambId).query(UUID.class).single();
        var manual = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/feedback/" + attrId + "/attribution",
                "r2-owner-a", Map.of("revisionId", revisionV1, "expectedVersion", 0), JsonNode.class);
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manual.getBody().path("status").asText()).isEqualTo("ATTRIBUTED");
        assertThat(manual.getBody().path("revisionId").asText()).isEqualTo(revisionV1.toString());

        post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-owner-a", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢，面料不错 FAB-9",
                "card", Map.of("revisionId", revisionV2)), JsonNode.class);
        post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-member-a2", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "不喜欢这版面料",
                "replyToId", attributedMessageId), JsonNode.class);
        var issues = auth("r2-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/issues?revisionId=" + revisionV2,
                JsonNode.class);
        boolean sawDedup = false;
        for (JsonNode issue : issues.getBody()) {
            if (issue.path("independentAuthors").asInt() >= 2) {
                sawDedup = true;
            }
        }
        assertThat(sawDedup).isTrue();

        jdbc.sql("""
                UPDATE survey_session
                SET intake_until = clock_timestamp() - interval '1 second'
                WHERE id = :id
                """).param("id", surveyId).update();
        var late = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r2-member-a", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "晚到的反馈"), JsonNode.class);
        // survey no longer links because intake window closed for new ACTIVE binding
        if (!late.getBody().path("surveyId").isNull()) {
            UUID lateId = UUID.fromString(late.getBody().path("id").asText());
            assertThat(jdbc.sql("SELECT status FROM message_attribution WHERE message_id = :id")
                    .param("id", lateId).query(String.class).optional().orElse("NONE"))
                    .isIn("OUT_OF_WINDOW", "NONE");
        }

        // restore window for later tests
        jdbc.sql("""
                UPDATE survey_session
                SET intake_until = clock_timestamp() + interval '2 hours',
                    closing_deadline_at = clock_timestamp() + interval '2 hours 10 minutes',
                    status = 'ACTIVE'
                WHERE id = :id
                """).param("id", surveyId).update();
    }

    @Test
    @Order(3)
    void hybridRetrievalLexicalVectorDegradeAndRevocation() {
        openaiMode.set(0);
        var search = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-a",
                Map.of("revisionId", revisionV2, "query", "FAB-9 fabric", "topK", 5), JsonNode.class);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(search.getBody().path("retrievalMode").asText()).isIn("hybrid", "lexical", "vector");
        assertThat(search.getBody().path("hits").size()).isGreaterThan(0);
        assertThat(search.getBody().path("hits").get(0).path("evidenceId").asText()).isNotBlank();

        openaiMode.set(3);
        var degraded = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-a",
                Map.of("revisionId", revisionV2, "query", "FAB-9", "topK", 5), JsonNode.class);
        assertThat(degraded.getBody().path("retrievalMode").asText()).contains("lexical");
        openaiMode.set(0);

        openaiMode.set(1);
        var rateLimited = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-a",
                Map.of("revisionId", revisionV2, "query", "FAB-9", "topK", 5), JsonNode.class);
        assertThat(rateLimited.getBody().path("retrievalMode").asText()).contains("lexical");
        openaiMode.set(0);

        openaiMode.set(2);
        var dimMismatch = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-a",
                Map.of("revisionId", revisionV2, "query", "FAB-9", "topK", 5), JsonNode.class);
        assertThat(dimMismatch.getBody().path("retrievalMode").asText()).contains("lexical");
        openaiMode.set(0);

        var foreignSearch = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-b",
                Map.of("revisionId", revisionV2, "query", "FAB-9", "topK", 5), JsonNode.class);
        assertThat(foreignSearch.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

        jdbc.sql("UPDATE evidence_ref SET visibility = 'REVOKED' WHERE id = :id")
                .param("id", evidenceId).update();
        jdbc.sql("UPDATE evidence_chunk SET visibility = 'REVOKED' WHERE evidence_id = :id")
                .param("id", evidenceId).update();
        var afterRevoke = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/retrieval/search", "r2-owner-a",
                Map.of("revisionId", revisionV2, "query", "FAB-9 fabric", "topK", 20), JsonNode.class);
        for (JsonNode hit : afterRevoke.getBody().path("hits")) {
            assertThat(hit.path("evidenceId").asText()).isNotEqualTo(evidenceId.toString());
        }
    }

    @Test
    @Order(4)
    void visionCompareGapsAndFabricatedEvidenceRejected() {
        var compare = post("/merchants/" + merchantA + "/vision/compare", "r2-owner-a", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV2,
                "surveyId", surveyId), JsonNode.class);
        assertThat(compare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(compare.getBody().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(compare.getBody().path("visibleDifferences").size()).isGreaterThan(0);
        assertThat(compare.getBody().path("evidenceId").isNull()).isFalse();

        UUID emptyStyleCampaign = UUID.fromString(post("/merchants/" + merchantA + "/campaigns", "r2-owner-a",
                Map.of("name", "NoImg"), JsonNode.class).getBody().path("id").asText());
        UUID emptyStyle = UUID.fromString(post("/merchants/" + merchantA + "/campaigns/" + emptyStyleCampaign + "/styles",
                "r2-owner-a", Map.of("name", "X", "category", "x"), JsonNode.class).getBody().path("id").asText());
        // create revision with asset then delete association? Asset required at create. Simulate missing by using same revision twice rejected.
        var same = post("/merchants/" + merchantA + "/vision/compare", "r2-owner-a", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV1), JsonNode.class);
        assertThat(same.getStatusCode().value()).isEqualTo(422);

        // model unavailable path: temporarily point? Use a revision pair but force 503 via request marker in stub
        // Stub checks FORCE_UNAVAILABLE in body; VisionService prompt is fixed. Restart: create gap via missing asset revision.
        UUID lonelyAsset = uploadAsset("r2-owner-a", merchantA, "image/png", "only".getBytes(StandardCharsets.UTF_8));
        UUID lonelyRev = UUID.fromString(post("/merchants/" + merchantA + "/styles/" + emptyStyle + "/revisions",
                "r2-owner-a", Map.of("assetIds", List.of(lonelyAsset), "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "Z", "materialName", "Z",
                        "quantity", 1, "unit", "pc", "costGap", true)), "notes", "lonely"), JsonNode.class)
                .getBody().path("id").asText());
        jdbc.sql("DELETE FROM revision_asset WHERE revision_id = :id").param("id", lonelyRev).update();
        var gap = post("/merchants/" + merchantA + "/vision/compare", "r2-owner-a", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", lonelyRev), JsonNode.class);
        assertThat(gap.getBody().path("status").asText()).isEqualTo("GAP");
        assertThat(gap.getBody().path("gapCode").asText()).contains("ASSET");

        qwenMode.set(2);
        var fabricated = post("/merchants/" + merchantA + "/vision/compare", "r2-owner-a", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV2,
                "surveyId", surveyId), JsonNode.class);
        assertThat(fabricated.getStatusCode().value()).isIn(409, 500);
        qwenMode.set(1);
        var unavailable = post("/merchants/" + merchantA + "/vision/compare", "r2-owner-a", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV2), JsonNode.class);
        assertThat(unavailable.getBody().path("status").asText()).isEqualTo("GAP");
        assertThat(unavailable.getBody().path("gapCode").asText()).isEqualTo("MODEL_UNAVAILABLE");
        qwenMode.set(0);

        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM model_call_attempt
                WHERE merchant_id = :m AND purpose IN ('embedding','vision_compare') AND status = 'SUCCEEDED'
                """).param("m", merchantA).query(Long.class).single()).isGreaterThan(0);

        var stats = auth("r2-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/feedback-stats?revisionId=" + revisionV2,
                JsonNode.class);
        assertThat(stats.getBody().path("attributedMessages").asLong()).isGreaterThan(0);
        assertThat(stats.getBody().path("opinionCount").asLong()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    void pgvectorExtensionAndTenantFkGuards() {
        assertThat(jdbc.sql("SELECT extname FROM pg_extension WHERE extname = 'vector'")
                .query(String.class).single()).isEqualTo("vector");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema='craftpilot_app' AND table_name IN
                ('message_attribution','feedback_opinion','feedback_issue','evidence_ref','evidence_chunk',
                 'vision_comparison','model_call_attempt','retrieval_execution')
                """).query(Long.class).single()).isEqualTo(8L);

        UUID foreignEvidence = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO evidence_ref
                    (merchant_id, id, survey_id, revision_id, source_type, source_id, captured_at, grade,
                     checksum, visibility, seq, summary)
                    VALUES (:m, :id, :survey, :revision, 'TEST', :source, clock_timestamp(), 'A',
                            'abc', 'ACTIVE', 99999, 'x')
                    """).param("m", merchantB).param("id", foreignEvidence).param("survey", surveyId)
                    .param("revision", revisionV2).param("source", UUID.randomUUID()).update();
            assertThat(false).as("cross-tenant evidence insert must fail").isTrue();
        } catch (Exception expected) {
            assertThat(expected.getMessage()).containsIgnoringCase("foreign");
        }
    }

    private UUID uploadAsset(String login, UUID merchant, String contentType, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "file.bin";
            }
        });
        var headers = new HttpHeaders();
        headers.setBasicAuth(login, PASSWORD);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private <T> ResponseEntity<T> post(String path, String login, Object body, Class<T> type) {
        return auth(login).exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body), type);
    }

    private <T> ResponseEntity<T> put(String path, String login, Object body, Class<T> type) {
        return auth(login).exchange("/api/v1" + path, HttpMethod.PUT, new HttpEntity<>(body), type);
    }

    private TestRestTemplate auth(String login) {
        return http.withBasicAuth(login, PASSWORD);
    }

    private static String vectorJson(float seed) {
        List<String> values = new ArrayList<>(1536);
        for (int i = 0; i < 1536; i++) {
            float v = (float) Math.sin(seed + i * 0.017) * 0.01f;
            values.add(Float.toString(v));
        }
        return "[" + String.join(",", values) + "]";
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static UUID user(JdbcClient jdbc, String login, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,:login)")
                .param("id", id).param("login", login).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:id,:hash,clock_timestamp()-interval '1 day', clock_timestamp() + interval '1 day')
                """).param("id", id).param("hash", hash).update();
        return id;
    }

    private static void addMembership(JdbcClient jdbc, UUID merchant, UUID principal, String role) {
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,:r)")
                .param("m", merchant).param("p", principal).param("r", role).update();
    }
}
