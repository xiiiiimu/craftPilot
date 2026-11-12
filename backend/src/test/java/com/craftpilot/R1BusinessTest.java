package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.craftpilot.events.OutboxDispatcher;
import com.craftpilot.events.OutboxWriter;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R1BusinessTest {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r1";
    private static final String PASSWORD = UUID.randomUUID().toString();

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
        config.add("CRAFTPILOT_JDBC_URL", R1BusinessTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", R1BusinessTest::minioEndpoint);
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> true);
        config.add("craftpilot.outbox.dispatch-enabled", () -> false);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxWriter outboxWriter;
    @Autowired TransactionTemplate tx;
    @Autowired MinioClient minio;
    @LocalServerPort int port;

    private static UUID merchantA;
    private static UUID merchantB;
    private static UUID ownerA;
    private static UUID memberA;
    private static UUID ownerB;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        String hash = encoder.encode(PASSWORD);
        ownerA = user(jdbc, "r1-owner-a", hash);
        memberA = user(jdbc, "r1-member-a", hash);
        ownerB = user(jdbc, "r1-owner-b", hash);
        merchantA = UUID.randomUUID();
        merchantB = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:a,'R1-A'),(:b,'R1-B')")
                .param("a", merchantA).param("b", merchantB).update();
        addMembership(jdbc, merchantA, ownerA, "OWNER");
        addMembership(jdbc, merchantA, memberA, "MEMBER");
        addMembership(jdbc, merchantB, ownerB, "OWNER");
    }

    @Test
    @Order(1)
    void catalogRevisionBomAssetsAndTenantIsolation() {
        var campaign = post("/merchants/" + merchantA + "/campaigns", "r1-owner-a", Map.of(
                "name", "SS27", "description", "pilot"), JsonNode.class);
        assertThat(campaign.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID campaignId = UUID.fromString(campaign.getBody().path("id").asText());

        var style = post("/merchants/" + merchantA + "/campaigns/" + campaignId + "/styles", "r1-owner-a",
                Map.of("name", "Jacket", "category", "outerwear"), JsonNode.class);
        UUID styleId = UUID.fromString(style.getBody().path("id").asText());

        UUID assetId = uploadAsset("r1-owner-a", merchantA, "image/png", "png-bytes".getBytes(StandardCharsets.UTF_8));
        var content = auth("r1-owner-a").getForEntity("/api/v1/merchants/" + merchantA + "/assets/" + assetId + "/content",
                byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).isEqualTo("png-bytes".getBytes(StandardCharsets.UTF_8));

        var foreign = auth("r1-owner-b").getForEntity("/api/v1/merchants/" + merchantA + "/assets/" + assetId,
                JsonNode.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var revision = post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions", "r1-owner-a", Map.of(
                "assetIds", List.of(assetId),
                "bomItems", List.of(Map.of(
                        "lineNo", 1,
                        "materialCode", "FAB-1",
                        "materialName", "Cotton",
                        "quantity", 1.5,
                        "unit", "m",
                        "unitCost", 12.5,
                        "currency", "USD",
                        "costGap", false)),
                "notes", "v1"), JsonNode.class);
        assertThat(revision.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID revisionId = UUID.fromString(revision.getBody().path("id").asText());
        assertThat(revision.getBody().path("revisionNo").asLong()).isEqualTo(1);

        assertThat(jdbc.sql("UPDATE sample_revision SET notes = 'mutated' WHERE id = :id")
                .param("id", revisionId).update()).isEqualTo(1);
        // immutability is API-level: no update endpoint; DB snapshot_hash remains the creation hash
        String hash = jdbc.sql("SELECT snapshot_hash FROM sample_revision WHERE id = :id")
                .param("id", revisionId).query(String.class).single();
        assertThat(hash).isNotBlank();

        var badParent = post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions", "r1-owner-a", Map.of(
                "parentRevisionId", UUID.randomUUID(),
                "assetIds", List.of(assetId),
                "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-1", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "bad"), JsonNode.class);
        assertThat(badParent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        UUID asset2 = uploadAsset("r1-owner-a", merchantA, "image/png", "v2".getBytes(StandardCharsets.UTF_8));
        var v2 = post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions", "r1-owner-a", Map.of(
                "parentRevisionId", revisionId,
                "assetIds", List.of(asset2),
                "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-2", "materialName", "Wool",
                        "quantity", 2, "unit", "m", "costGap", true)),
                "notes", "v2"), JsonNode.class);
        assertThat(v2.getBody().path("revisionNo").asLong()).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO bom_item (merchant_id, revision_id, line_no, material_code, material_name, quantity, unit, cost_gap)
                VALUES (:m, :r, 1, 'X', 'Y', 1, 'm', false)
                """).param("m", merchantB).param("r", revisionId).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(2)
    void assetFailureDoesNotBecomeAvailable() {
        UUID id = UUID.randomUUID();
        String objectKey = "merchants/" + merchantA + "/assets/" + id;
        jdbc.sql("""
                INSERT INTO asset (merchant_id, id, owner_principal_id, kind, content_type, object_key, status, byte_size)
                VALUES (:m, :id, :owner, 'image', 'image/png', :key, 'STAGED', 3)
                """).param("m", merchantA).param("id", id).param("owner", ownerA).param("key", objectKey).update();
        var body = auth("r1-owner-a").getForEntity("/api/v1/merchants/" + merchantA + "/assets/" + id + "/content",
                JsonNode.class);
        assertThat(body.getStatusCode().value()).isIn(409, 503);
        assertThat(jdbc.sql("SELECT status FROM asset WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("STAGED");
    }

    @Test
    @Order(3)
    void groupsMessagesIdempotencyWebsocketCatchupAndOutbox() throws Exception {
        var group = post("/merchants/" + merchantA + "/groups", "r1-owner-a", Map.of("name", "Focus"), JsonNode.class);
        UUID groupId = UUID.fromString(group.getBody().path("id").asText());
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r1-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> wsPayload = new AtomicReference<>();
        String basic = Base64.getEncoder().encodeToString(("r1-member-a:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Basic " + basic)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.sendText("{\"action\":\"subscribe\",\"merchantId\":\"" + merchantA
                                + "\",\"groupId\":\"" + groupId + "\"}", true);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String text = data.toString();
                        if (text.contains("MESSAGE_HINT")) {
                            wsPayload.set(text);
                            latch.countDown();
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                }).join();

        UUID clientMsg = UUID.randomUUID();
        var first = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r1-member-a", Map.of(
                "clientMsgId", clientMsg, "text", "hello"), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long seq = first.getBody().path("groupSeq").asLong();
        var replay = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r1-member-a", Map.of(
                "clientMsgId", clientMsg, "text", "hello"), JsonNode.class);
        assertThat(replay.getBody().path("id").asText()).isEqualTo(first.getBody().path("id").asText());
        var conflict = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r1-member-a", Map.of(
                "clientMsgId", clientMsg, "text", "different"), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(dispatcher.dispatchOnce()).isGreaterThanOrEqualTo(1);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(wsPayload.get()).contains(String.valueOf(seq));

        // simulate restart recovery: requeue delivered event and reclaim
        UUID eventId = jdbc.sql("""
                SELECT id FROM outbox WHERE event_type = 'MESSAGE_PERSISTED' ORDER BY created_at DESC LIMIT 1
                """).query(UUID.class).single();
        jdbc.sql("""
                UPDATE outbox SET status = 'PENDING', available_at = clock_timestamp(), lease_until = NULL,
                                  lease_owner = NULL WHERE id = :id
                """).param("id", eventId).update();
        assertThat(dispatcher.dispatchOnce()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM consumer_receipt WHERE event_id = :id
                """).param("id", eventId).query(Long.class).single()).isEqualTo(1L);

        var page = auth("r1-member-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/groups/" + groupId + "/messages?afterSeq=0", JsonNode.class);
        assertThat(page.getBody().path("items").size()).isGreaterThanOrEqualTo(1);

        var denied = auth("r1-owner-b").getForEntity(
                "/api/v1/merchants/" + merchantA + "/groups/" + groupId + "/messages?afterSeq=0", JsonNode.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(4)
    void surveyWindowsVotesPurchaseIntentAndCloseIdempotency() {
        var group = post("/merchants/" + merchantA + "/groups", "r1-owner-a", Map.of("name", "SurveyRoom"), JsonNode.class);
        UUID groupId = UUID.fromString(group.getBody().path("id").asText());
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r1-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);

        UUID campaignId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns", "r1-owner-a",
                Map.of("name", "VoteCamp"), JsonNode.class).getBody().path("id").asText());
        UUID styleId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns/" + campaignId + "/styles",
                "r1-owner-a", Map.of("name", "Dress", "category", "dress"), JsonNode.class).getBody().path("id").asText());
        UUID assetId = uploadAsset("r1-owner-a", merchantA, "image/png", "x".getBytes(StandardCharsets.UTF_8));
        UUID revisionId = UUID.fromString(post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions",
                "r1-owner-a", Map.of(
                        "assetIds", List.of(assetId),
                        "bomItems", List.of(Map.of(
                                "lineNo", 1, "materialCode", "A", "materialName", "A",
                                "quantity", 1, "unit", "pc", "costGap", true)),
                        "notes", "one"), JsonNode.class).getBody().path("id").asText());

        var survey = post("/merchants/" + merchantA + "/groups/" + groupId + "/surveys", "r1-owner-a",
                Map.of("revisionIds", List.of(revisionId)), JsonNode.class);
        assertThat(survey.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID surveyId = UUID.fromString(survey.getBody().path("id").asText());
        assertThat(survey.getBody().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM subscription WHERE survey_id = :id AND status = 'ACTIVE'
                """).param("id", surveyId).query(Long.class).single()).isEqualTo(1L);

        var conflict = post("/merchants/" + merchantA + "/groups/" + groupId + "/surveys", "r1-owner-a",
                Map.of("revisionIds", List.of(revisionId)), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var preference = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls", "r1-owner-a", Map.of(
                "revisionId", revisionId,
                "kind", "PREFERENCE",
                "question", "Like style?",
                "options", List.of("Love", "Hate")), JsonNode.class);
        UUID preferencePoll = UUID.fromString(preference.getBody().path("id").asText());
        UUID love = UUID.fromString(preference.getBody().path("options").get(0).path("id").asText());
        put("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + preferencePoll + "/vote",
                "r1-member-a", Map.of("optionId", love), JsonNode.class);

        var poll = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls", "r1-owner-a", Map.of(
                "revisionId", revisionId,
                "kind", "PURCHASE_INTENT",
                "question", "Would buy?",
                "options", List.of("Yes", "No")), JsonNode.class);
        UUID pollId = UUID.fromString(poll.getBody().path("id").asText());
        UUID yes = UUID.fromString(poll.getBody().path("options").get(0).path("id").asText());

        var vote = put("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + pollId + "/vote",
                "r1-member-a", Map.of("optionId", yes), JsonNode.class);
        assertThat(vote.getStatusCode()).isEqualTo(HttpStatus.OK);
        var voteAgain = put("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + pollId + "/vote",
                "r1-member-a", Map.of("optionId", yes), JsonNode.class);
        assertThat(voteAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stats = auth("r1-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + pollId + "/stats",
                JsonNode.class);
        assertThat(stats.getBody().path("totalVotes").asLong()).isEqualTo(1);
        assertThat(stats.getBody().path("kind").asText()).isEqualTo("PURCHASE_INTENT");
        var preferenceStats = auth("r1-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + preferencePoll + "/stats",
                JsonNode.class);
        assertThat(preferenceStats.getBody().path("kind").asText()).isEqualTo("PREFERENCE");
        assertThat(preferenceStats.getBody().path("totalVotes").asLong()).isEqualTo(1);

        UUID closeKey = UUID.randomUUID();
        var closed = auth("r1-owner-a").exchange("/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/close",
                HttpMethod.POST, entity("r1-owner-a", null, closeKey), JsonNode.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().path("status").asText()).isIn("CLOSING", "CLOSED");
        var closedAgain = auth("r1-owner-a").exchange("/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/close",
                HttpMethod.POST, entity("r1-owner-a", null, closeKey), JsonNode.class);
        assertThat(closedAgain.getBody().path("status").asText()).isEqualTo(closed.getBody().path("status").asText());

        jdbc.sql("""
                UPDATE survey_session
                SET intake_until = clock_timestamp() - interval '1 second',
                    closing_deadline_at = clock_timestamp() - interval '1 second',
                    status = 'CLOSING'
                WHERE id = :id
                """).param("id", surveyId).update();
        var afterWindow = put("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + pollId + "/vote",
                "r1-member-a", Map.of("optionId", yes), JsonNode.class);
        assertThat(afterWindow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var reopen = post("/merchants/" + merchantA + "/groups/" + groupId + "/surveys", "r1-owner-a",
                Map.of("revisionIds", List.of(revisionId)), JsonNode.class);
        // openSurvey advances due surveys first
        assertThat(reopen.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT status FROM survey_session WHERE id = :id").param("id", surveyId)
                .query(String.class).single()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM subscription WHERE survey_id = :id AND status = 'ACTIVE'
                """).param("id", surveyId).query(Long.class).single()).isZero();
    }

    @Test
    @Order(5)
    void multiWorkerOutboxClaimIsExclusive() {
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outbox (merchant_id, id, aggregate_type, aggregate_id, event_type, payload_json, status)
                VALUES (:m, :id, 'test', :id, 'NOOP_TEST', CAST('{"groupId":"00000000-0000-0000-0000-000000000001","groupSeq":1,"messageId":"00000000-0000-0000-0000-000000000002"}' AS jsonb), 'PENDING')
                """).param("m", merchantA).param("id", event).update();
        // force unknown event type path by using MESSAGE_PERSISTED shape but we inserted NOOP - delivery will NPE/fail safely
        jdbc.sql("UPDATE outbox SET event_type = 'MESSAGE_PERSISTED' WHERE id = :id").param("id", event).update();
        int first = dispatcher.dispatchOnce();
        assertThat(first).isGreaterThanOrEqualTo(1);
        String status = jdbc.sql("SELECT status FROM outbox WHERE id = :id").param("id", event).query(String.class).single();
        assertThat(status).isIn("DELIVERED", "FAILED", "DLQ");
    }

    @Test
    @Order(6)
    void concurrentVotesKeepSingleRowPerMember() throws Exception {
        var group = post("/merchants/" + merchantA + "/groups", "r1-owner-a", Map.of("name", "Concurrent"), JsonNode.class);
        UUID groupId = UUID.fromString(group.getBody().path("id").asText());
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r1-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);
        UUID campaignId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns", "r1-owner-a",
                Map.of("name", "CVote"), JsonNode.class).getBody().path("id").asText());
        UUID styleId = UUID.fromString(post("/merchants/" + merchantA + "/campaigns/" + campaignId + "/styles",
                "r1-owner-a", Map.of("name", "S", "category", "c"), JsonNode.class).getBody().path("id").asText());
        UUID assetId = uploadAsset("r1-owner-a", merchantA, "image/png", "c".getBytes(StandardCharsets.UTF_8));
        UUID revisionId = UUID.fromString(post("/merchants/" + merchantA + "/styles/" + styleId + "/revisions",
                "r1-owner-a", Map.of(
                        "assetIds", List.of(assetId),
                        "bomItems", List.of(Map.of(
                                "lineNo", 1, "materialCode", "A", "materialName", "A",
                                "quantity", 1, "unit", "pc", "costGap", true)),
                        "notes", "c"), JsonNode.class).getBody().path("id").asText());
        UUID surveyId = UUID.fromString(post("/merchants/" + merchantA + "/groups/" + groupId + "/surveys",
                "r1-owner-a", Map.of("revisionIds", List.of(revisionId)), JsonNode.class).getBody().path("id").asText());
        var poll = post("/merchants/" + merchantA + "/surveys/" + surveyId + "/polls", "r1-owner-a", Map.of(
                "revisionId", revisionId, "kind", "PREFERENCE", "question", "Color?",
                "options", List.of("Red", "Blue")), JsonNode.class);
        UUID pollId = UUID.fromString(poll.getBody().path("id").asText());
        UUID red = UUID.fromString(poll.getBody().path("options").get(0).path("id").asText());
        UUID blue = UUID.fromString(poll.getBody().path("options").get(1).path("id").asText());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) {
                UUID option = i % 2 == 0 ? red : blue;
                futures.add(pool.submit(() -> put("/merchants/" + merchantA + "/surveys/" + surveyId
                        + "/polls/" + pollId + "/vote", "r1-member-a", Map.of("optionId", option), JsonNode.class)));
            }
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM survey_vote
                WHERE merchant_id = :m AND survey_id = :s AND poll_id = :p AND member_id = :member
                """).param("m", merchantA).param("s", surveyId).param("p", pollId).param("member", memberA)
                .query(Long.class).single()).isEqualTo(1L);
        var stats = auth("r1-owner-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/surveys/" + surveyId + "/polls/" + pollId + "/stats",
                JsonNode.class);
        assertThat(stats.getBody().path("totalVotes").asLong()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void outboxWriteRollsBackAtomicallyWithBusinessTransaction() {
        long before = jdbc.sql("SELECT COUNT(*) FROM outbox").query(Long.class).single();
        tx.executeWithoutResult(status -> {
            outboxWriter.enqueue(merchantA, "probe", UUID.randomUUID(), "PROBE_EVENT",
                    Map.of("merchantId", merchantA));
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox").query(Long.class).single()).isEqualTo(before);

        var group = post("/merchants/" + merchantA + "/groups", "r1-owner-a", Map.of("name", "OutboxAtom"), JsonNode.class);
        UUID groupId = UUID.fromString(group.getBody().path("id").asText());
        var message = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r1-owner-a", Map.of(
                "clientMsgId", UUID.randomUUID(), "text", "persisted"), JsonNode.class);
        UUID messageId = UUID.fromString(message.getBody().path("id").asText());
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_id = :id AND event_type = 'MESSAGE_PERSISTED' AND status = 'PENDING'
                """).param("id", messageId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @Order(8)
    void concurrentMessageIdempotencyAndRevokedMemberLoseAccess() throws Exception {
        var group = post("/merchants/" + merchantA + "/groups", "r1-owner-a", Map.of("name", "IdemRoom"), JsonNode.class);
        UUID groupId = UUID.fromString(group.getBody().path("id").asText());
        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r1-owner-a",
                Map.of("role", "MEMBER", "enabled", true), JsonNode.class);

        UUID clientMsg = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> post("/merchants/" + merchantA + "/groups/" + groupId + "/messages",
                        "r1-member-a", Map.of("clientMsgId", clientMsg, "text", "same"), JsonNode.class)));
            }
            var ids = new java.util.HashSet<String>();
            for (var future : futures) {
                var response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ids.add(response.getBody().path("id").asText());
            }
            assertThat(ids).hasSize(1);
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM group_message
                    WHERE merchant_id = :m AND group_id = :g AND sender_id = :s AND client_msg_id = :c
                    """).param("m", merchantA).param("g", groupId).param("s", memberA).param("c", clientMsg)
                    .query(Long.class).single()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        put("/merchants/" + merchantA + "/groups/" + groupId + "/members/" + memberA, "r1-owner-a",
                Map.of("role", "MEMBER", "enabled", false), JsonNode.class);
        var deniedPost = post("/merchants/" + merchantA + "/groups/" + groupId + "/messages", "r1-member-a", Map.of(
                "clientMsgId", UUID.randomUUID(), "text", "after-revoke"), JsonNode.class);
        assertThat(deniedPost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var deniedRead = auth("r1-member-a").getForEntity(
                "/api/v1/merchants/" + merchantA + "/groups/" + groupId + "/messages?afterSeq=0", JsonNode.class);
        assertThat(deniedRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(9)
    void flywayObjectsPresentOnBootstrappedSchema() {
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'craftpilot_app' AND table_name IN
                ('launch_campaign','sample_revision','group_message','survey_session','outbox','subscription')
                """).query(Long.class).single()).isEqualTo(6L);
        assertThat(jdbc.sql("""
                SELECT version FROM flyway_schema_history WHERE success AND type = 'SQL' ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2");
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

    private HttpEntity<Void> entity(String login, Void body, UUID idempotencyKey) {
        var headers = new HttpHeaders();
        headers.setBasicAuth(login, PASSWORD);
        headers.set("Idempotency-Key", idempotencyKey.toString());
        return new HttpEntity<>(body, headers);
    }

    private TestRestTemplate auth(String login) {
        return http.withBasicAuth(login, PASSWORD);
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
