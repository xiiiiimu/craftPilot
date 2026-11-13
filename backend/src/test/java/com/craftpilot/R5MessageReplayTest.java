package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.r5.R5TestSupport;
import com.craftpilot.r5.R5TestSupport.MessageSpec;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Deterministic 1200-message business replay across merchants/groups/members.
 * Exercises real GroupService idempotency, survey, votes, and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R5MessageReplayTest {
    private static final String MINIO_USER = "r5-replay-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r5-replay";
    private static final String PASSWORD = "r5-replay-" + UUID.randomUUID();
    private static final Path RESULTS = Path.of("..").toAbsolutePath().normalize()
            .resolve("docs/development/R5-1200-message-replay-results.md");
    private static final Map<String, String> METRICS = new LinkedHashMap<>();

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
        config.add("CRAFTPILOT_JDBC_URL", R5MessageReplayTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> "true");
        config.add("craftpilot.outbox.dispatch-enabled", () -> "false");
        config.add("craftpilot.runtime.worker-enabled", () -> "false");
        config.add("craftpilot.openai.base-url", () -> "http://127.0.0.1:9");
        config.add("craftpilot.openai.api-key", () -> "unused");
        config.add("craftpilot.chat.base-url", () -> "http://127.0.0.1:9");
        config.add("craftpilot.chat.api-key", () -> "unused");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;

    static final List<MerchantBundle> MERCHANTS = new ArrayList<>();

    record MerchantBundle(UUID merchantId, String ownerLogin, UUID groupId, UUID revisionId, UUID surveyId,
                          List<String> memberLogins, UUID pollId, UUID buyOptionId) {}

    @BeforeAll
    static void minioBucket(@Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @Test
    @Order(1)
    void bootstrapMultiMerchantDomain() {
        for (int m = 0; m < 3; m++) {
            UUID owner = UUID.randomUUID();
            UUID merchant = UUID.randomUUID();
            String ownerLogin = "r5-owner-" + m;
            jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,:name)")
                    .param("id", owner).param("login", ownerLogin).param("name", "R5 Owner " + m).update();
            jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,:name)")
                    .param("id", merchant).param("name", "R5 Merchant " + m).update();
            jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                    .param("m", merchant).param("p", owner).update();
            jdbc.sql("""
                    INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                    VALUES (:p,:h,clock_timestamp()-interval '1 day', clock_timestamp()+interval '2 days')
                    """).param("p", owner).param("h", encoder.encode(PASSWORD)).update();

            List<String> members = new ArrayList<>();
            members.add(ownerLogin);
            for (int i = 0; i < 3; i++) {
                UUID mid = UUID.randomUUID();
                String login = "r5-m" + m + "-u" + i;
                jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,:name)")
                        .param("id", mid).param("login", login).param("name", login).update();
                jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'MEMBER')")
                        .param("m", merchant).param("p", mid).update();
                jdbc.sql("""
                        INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                        VALUES (:p,:h,clock_timestamp()-interval '1 day', clock_timestamp()+interval '2 days')
                        """).param("p", mid).param("h", encoder.encode(PASSWORD)).update();
                members.add(login);
            }

            UUID groupId = UUID.fromString(post(merchant, ownerLogin, "/merchants/" + merchant + "/groups",
                    Map.of("name", "R5Room-" + m)).getBody().path("id").asText());
            for (String login : members) {
                if (login.equals(ownerLogin)) {
                    continue;
                }
                UUID principal = jdbc.sql("SELECT id FROM principal WHERE login = :l").param("l", login)
                        .query(UUID.class).single();
                put(merchant, ownerLogin, "/merchants/" + merchant + "/groups/" + groupId + "/members/" + principal,
                        Map.of("role", "MEMBER", "enabled", true));
            }
            UUID campaign = UUID.fromString(post(merchant, ownerLogin, "/merchants/" + merchant + "/campaigns",
                    Map.of("name", "R5Camp-" + m)).getBody().path("id").asText());
            UUID style = UUID.fromString(post(merchant, ownerLogin,
                    "/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                    Map.of("name", "Style-" + m, "category", "wear")).getBody().path("id").asText());
            UUID asset = uploadAsset(merchant, ownerLogin, "r5-" + m + ".png");
            UUID revision = UUID.fromString(post(merchant, ownerLogin,
                    "/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                            "assetIds", List.of(asset),
                            "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "FAB-" + m,
                                    "materialName", "Cotton-" + m, "quantity", 1, "unit", "m", "costGap", true)),
                            "notes", "v1-r5-" + m)).getBody().path("id").asText());
            UUID survey = UUID.fromString(post(merchant, ownerLogin,
                    "/merchants/" + merchant + "/groups/" + groupId + "/surveys",
                    Map.of("revisionIds", List.of(revision))).getBody().path("id").asText());
            var poll = post(merchant, ownerLogin, "/merchants/" + merchant + "/surveys/" + survey + "/polls",
                    Map.of("revisionId", revision, "kind", "PURCHASE_INTENT", "question", "Would buy?",
                            "options", List.of("Yes", "No")));
            UUID pollId = UUID.fromString(poll.getBody().path("id").asText());
            UUID buyOption = UUID.fromString(poll.getBody().path("options").get(0).path("id").asText());
            MERCHANTS.add(new MerchantBundle(merchant, ownerLogin, groupId, revision, survey, members, pollId,
                    buyOption));
        }
        METRICS.put("merchants", String.valueOf(MERCHANTS.size()));
        METRICS.put("groups", String.valueOf(MERCHANTS.size()));
        METRICS.put("seed", String.valueOf(R5TestSupport.REPLAY_SEED));
        assertThat(MERCHANTS).hasSize(3);
    }

    @Test
    @Order(2)
    void replay1200MessagesWithDuplicatesVotesAndIsolation() throws Exception {
        Instant start = Instant.now();
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicatesAccepted = new AtomicInteger();
        AtomicInteger expectedRejects = new AtomicInteger();
        AtomicInteger unexpectedRejects = new AtomicInteger();
        AtomicInteger transportErrors = new AtomicInteger();
        AtomicInteger votes = new AtomicInteger();
        AtomicInteger expected409 = new AtomicInteger();
        AtomicInteger expected403 = new AtomicInteger();
        Map<String, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> expectedRejectReasons = new ConcurrentHashMap<>();
        List<String> rejectSamples = Collections.synchronizedList(new ArrayList<>());

        int perMerchant = R5TestSupport.TARGET_MESSAGES / MERCHANTS.size();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<>();
        for (int mi = 0; mi < MERCHANTS.size(); mi++) {
            MerchantBundle bundle = MERCHANTS.get(mi);
            List<MessageSpec> corpus = R5TestSupport.buildMessageCorpus(perMerchant, R5TestSupport.REPLAY_SEED + mi,
                    bundle.memberLogins(), bundle.revisionId());
            futures.add(pool.submit(() -> {
                for (int i = 0; i < corpus.size(); i++) {
                    MessageSpec spec = corpus.get(i);
                    posted.incrementAndGet();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("clientMsgId", spec.clientMsgId());
                    body.put("text", spec.text());
                    // Sample cards require group ADMIN/OWNER; members post text-only feedback.
                    if (spec.withCard() && spec.login().equals(bundle.ownerLogin())) {
                        body.put("card", Map.of("revisionId", bundle.revisionId()));
                    }
                    try {
                        var resp = post(bundle.merchantId(), spec.login(),
                                "/merchants/" + bundle.merchantId() + "/groups/" + bundle.groupId() + "/messages",
                                body);
                        int code = resp.getStatusCode().value();
                        statusCounts.computeIfAbsent(String.valueOf(code), k -> new AtomicInteger()).incrementAndGet();
                        if (resp.getStatusCode().is2xxSuccessful()) {
                            accepted.incrementAndGet();
                            if (spec.duplicateOfPrior()) {
                                duplicatesAccepted.incrementAndGet();
                            }
                        } else if (code == 409 && spec.duplicateOfPrior()) {
                            // Same sender + clientMsgId with different payload → idempotency conflict.
                            expectedRejects.incrementAndGet();
                            expected409.incrementAndGet();
                            String reason = resp.getBody() != null && resp.getBody().has("code")
                                    ? resp.getBody().path("code").asText("idempotencyConflict")
                                    : "idempotencyConflict";
                            expectedRejectReasons.computeIfAbsent(reason, k -> new AtomicInteger())
                                    .incrementAndGet();
                            if (rejectSamples.size() < 12) {
                                rejectSamples.add("409 " + reason + " login=" + spec.login()
                                        + " clientMsgId=" + spec.clientMsgId()
                                        + " dupCorpus=true");
                            }
                        } else if (code == 403 && spec.withCard() && !spec.login().equals(bundle.ownerLogin())) {
                            expectedRejects.incrementAndGet();
                            expected403.incrementAndGet();
                            expectedRejectReasons.computeIfAbsent("card_forbidden", k -> new AtomicInteger())
                                    .incrementAndGet();
                            if (rejectSamples.size() < 12) {
                                rejectSamples.add("403 card_forbidden login=" + spec.login());
                            }
                        } else {
                            unexpectedRejects.incrementAndGet();
                            if (rejectSamples.size() < 12) {
                                String errBody = resp.getBody() == null ? "" : resp.getBody().toString();
                                rejectSamples.add("UNEXPECTED " + code + " login=" + spec.login()
                                        + " dup=" + spec.duplicateOfPrior() + " body=" + errBody);
                            }
                        }
                    } catch (Exception ex) {
                        transportErrors.incrementAndGet();
                        statusCounts.computeIfAbsent("EX", k -> new AtomicInteger()).incrementAndGet();
                    }
                    if (i % 40 == 0) {
                        try {
                            put(bundle.merchantId(), spec.login(),
                                    "/merchants/" + bundle.merchantId() + "/surveys/" + bundle.surveyId()
                                            + "/polls/" + bundle.pollId() + "/vote",
                                    Map.of("optionId", bundle.buyOptionId()));
                            votes.incrementAndGet();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.MINUTES);
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        long dbMessages = jdbc.sql("SELECT COUNT(*) FROM group_message").query(Long.class).single();
        long uniqueClient = jdbc.sql("SELECT COUNT(DISTINCT client_msg_id) FROM group_message")
                .query(Long.class).single();
        long uniqueSenderClient = jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT merchant_id, group_id, sender_id, client_msg_id FROM group_message
                ) t
                """).query(Long.class).single();
        long evidence = jdbc.sql("SELECT COUNT(*) FROM evidence_ref").query(Long.class).single();
        long voteRows = jdbc.sql("SELECT COUNT(*) FROM survey_vote").query(Long.class).single();
        long issues = jdbc.sql("SELECT COUNT(*) FROM feedback_issue").query(Long.class).optional().orElse(0L);
        long outboxMessageEvents = jdbc.sql("""
                SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'group_message' AND event_type = 'MESSAGE_PERSISTED'
                """).query(Long.class).single();

        // Cross-tenant isolation: member of merchant0 cannot read merchant1 messages
        MerchantBundle a = MERCHANTS.get(0);
        MerchantBundle b = MERCHANTS.get(1);
        var cross = rest.withBasicAuth(a.ownerLogin(), PASSWORD).getForEntity(
                "/api/v1/merchants/" + b.merchantId() + "/groups/" + b.groupId() + "/messages?afterSeq=0",
                JsonNode.class);
        assertThat(cross.getStatusCode().is4xxClientError()).isTrue();

        // Idempotent duplicate: replaying same clientMsgId + same payload does not grow rows
        MessageSpec first = R5TestSupport.buildMessageCorpus(1, R5TestSupport.REPLAY_SEED,
                a.memberLogins(), a.revisionId()).get(0);
        Map<String, Object> samePayload = new LinkedHashMap<>();
        samePayload.put("clientMsgId", first.clientMsgId());
        samePayload.put("text", first.text());
        if (first.withCard() && first.login().equals(a.ownerLogin())) {
            samePayload.put("card", Map.of("revisionId", a.revisionId()));
        }
        var idempotent = post(a.merchantId(), first.login(),
                "/merchants/" + a.merchantId() + "/groups/" + a.groupId() + "/messages", samePayload);
        assertThat(idempotent.getStatusCode().is2xxSuccessful()).isTrue();
        long afterDup = jdbc.sql("""
                SELECT COUNT(*) FROM group_message
                WHERE merchant_id = :m AND group_id = :g AND sender_id = (
                    SELECT id FROM principal WHERE login = :login
                ) AND client_msg_id = :c
                """).param("m", a.merchantId()).param("g", a.groupId()).param("login", first.login())
                .param("c", first.clientMsgId()).query(Long.class).single();
        assertThat(afterDup).isEqualTo(1L);

        // Conflicting payload for same key must be rejected (expected 409), not silently overwritten.
        var conflict = post(a.merchantId(), first.login(),
                "/merchants/" + a.merchantId() + "/groups/" + a.groupId() + "/messages",
                Map.of("clientMsgId", first.clientMsgId(), "text", "DIFFERENT-PAYLOAD-" + UUID.randomUUID()));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);

        int nonAccepted = expectedRejects.get() + unexpectedRejects.get() + transportErrors.get();
        assertThat(posted.get()).isEqualTo(R5TestSupport.TARGET_MESSAGES);
        assertThat(accepted.get()).isEqualTo(dbMessages);
        assertThat(accepted.get() + nonAccepted).isEqualTo(posted.get());
        assertThat(uniqueSenderClient).isEqualTo(dbMessages);
        assertThat(uniqueClient).isGreaterThan(900L);
        assertThat(unexpectedRejects.get()).as("unexpected HTTP rejects").isZero();
        assertThat(transportErrors.get()).as("transport/exceptions").isZero();
        assertThat(outboxMessageEvents).isEqualTo(dbMessages);
        assertThat(voteRows).isGreaterThan(0L);

        // Light runtime trigger per merchant (offline — worker disabled; tickets only).
        long ticketsBefore = jdbc.sql("SELECT COUNT(*) FROM ticket").query(Long.class).single();
        long outboxBefore = jdbc.sql("SELECT COUNT(*) FROM outbox").query(Long.class).single();
        for (MerchantBundle bundle : MERCHANTS) {
            HttpHeaders ticketHeaders = headers(bundle.ownerLogin());
            ticketHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
            var ticketResp = rest.exchange("/api/v1/merchants/" + bundle.merchantId() + "/surveys/"
                            + bundle.surveyId() + "/tickets",
                    HttpMethod.POST, new HttpEntity<>(Map.of(
                            "revisionIds", List.of(bundle.revisionId()),
                            "objective", "R5 replay research",
                            "workflowId", "launch-supervise-r4"), ticketHeaders), JsonNode.class);
            assertThat(ticketResp.getStatusCode().is2xxSuccessful()).isTrue();
        }
        long ticketsAfter = jdbc.sql("SELECT COUNT(*) FROM ticket").query(Long.class).single();
        long runs = jdbc.sql("SELECT COUNT(*) FROM agent_run").query(Long.class).single();
        long outboxAfter = jdbc.sql("SELECT COUNT(*) FROM outbox").query(Long.class).single();
        assertThat(ticketsAfter - ticketsBefore).isEqualTo(3L);
        assertThat(runs).isEqualTo(3L);
        assertThat(outboxAfter).isGreaterThan(outboxBefore);

        StringBuilder statusSummary = new StringBuilder();
        statusCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (statusSummary.length() > 0) {
                statusSummary.append(',');
            }
            statusSummary.append(e.getKey()).append('=').append(e.getValue().get());
        });

        METRICS.put("target_messages", String.valueOf(R5TestSupport.TARGET_MESSAGES));
        METRICS.put("posted_attempts", String.valueOf(posted.get()));
        METRICS.put("accepted_http_2xx", String.valueOf(accepted.get()));
        METRICS.put("http_status_histogram", statusSummary.toString());
        METRICS.put("duplicate_corpus_flags_accepted_2xx", String.valueOf(duplicatesAccepted.get()));
        METRICS.put("expected_rejects_409_or_403", String.valueOf(expectedRejects.get()));
        METRICS.put("expected_rejects_409", String.valueOf(expected409.get()));
        METRICS.put("expected_rejects_403", String.valueOf(expected403.get()));
        StringBuilder reasonSummary = new StringBuilder();
        expectedRejectReasons.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (reasonSummary.length() > 0) {
                reasonSummary.append(',');
            }
            reasonSummary.append(e.getKey()).append('=').append(e.getValue().get());
        });
        METRICS.put("expected_reject_reason_codes", reasonSummary.length() == 0 ? "none" : reasonSummary.toString());
        METRICS.put("reject_samples", rejectSamples.isEmpty() ? "none" : String.join(" | ", rejectSamples));
        METRICS.put("unexpected_rejects", String.valueOf(unexpectedRejects.get()));
        METRICS.put("transport_errors", String.valueOf(transportErrors.get()));
        METRICS.put("non_accepted_total", String.valueOf(nonAccepted));
        METRICS.put("reject_classification",
                "expected=idempotency_conflict(409 same sender+clientMsgId different payload) "
                        + "or card_forbidden(403 non-admin card); unexpected must be 0; "
                        + "accepted 2xx includes true idempotent retries (same payload)");
        METRICS.put("db_messages", String.valueOf(dbMessages));
        METRICS.put("unique_client_msg_ids", String.valueOf(uniqueClient));
        METRICS.put("unique_sender_client_keys", String.valueOf(uniqueSenderClient));
        METRICS.put("outbox_message_persisted", String.valueOf(outboxMessageEvents));
        METRICS.put("evidence_refs", String.valueOf(evidence));
        METRICS.put("feedback_issues", String.valueOf(issues));
        METRICS.put("survey_votes", String.valueOf(voteRows));
        METRICS.put("vote_attempts", String.valueOf(votes.get()));
        METRICS.put("elapsed_ms", String.valueOf(elapsedMs));
        METRICS.put("throughput_msg_per_sec",
                String.format(java.util.Locale.ROOT, "%.2f", accepted.get() * 1000.0 / Math.max(1, elapsedMs)));
        METRICS.put("cross_tenant_denied", "true");
        METRICS.put("idempotent_same_payload_rows", String.valueOf(afterDup));
        METRICS.put("idempotent_conflict_status", String.valueOf(conflict.getStatusCode().value()));
        METRICS.put("tickets_created", String.valueOf(ticketsAfter - ticketsBefore));
        METRICS.put("agent_runs_queued", String.valueOf(runs));
        METRICS.put("outbox_grew_on_tickets", String.valueOf(outboxAfter > outboxBefore));
        METRICS.put("outcome", unexpectedRejects.get() == 0 && transportErrors.get() == 0 ? "PASSED" : "FAILED");

        writeResults();
    }

    private void writeResults() throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# R5 1200-message replay results\n\n");
        md.append("Deterministic corpus seed `").append(R5TestSupport.REPLAY_SEED)
                .append("`. Messages posted through real Group Chat APIs.\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        for (var e : METRICS.entrySet()) {
            md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        md.append("\n### Rejection taxonomy\n\n");
        md.append("- **Accepted (2xx):** new messages and idempotent replays with identical payload.\n");
        md.append("- **Expected reject (409):** corpus `duplicateOfPrior` reused `clientMsgId` for the same ");
        md.append("sender with a *different* text/card → `ApiFailure.idempotencyConflict`.\n");
        md.append("- **Expected reject (403):** non-admin posting a sample card (guarded in corpus by ");
        md.append("stripping cards for members; residual counted if any).\n");
        md.append("- **Unexpected:** any other 4xx/5xx or transport exception — must be zero.\n");
        md.append("\n**Mode:** offline automated (no paid model).\n");
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, md.toString(), StandardCharsets.UTF_8);
    }

    private ResponseEntity<JsonNode> post(UUID merchant, String login, String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, headers(login)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(UUID merchant, String login, String path, Object body) {
        return rest.exchange("/api/v1" + path, HttpMethod.PUT, new HttpEntity<>(body, headers(login)),
                JsonNode.class);
    }

    private HttpHeaders headers(String login) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(login, PASSWORD);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID uploadAsset(UUID merchant, String login, String name) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource(("r5-" + name).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        HttpHeaders h = headers(login);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String jdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=craftpilot_app,public";
    }
}
