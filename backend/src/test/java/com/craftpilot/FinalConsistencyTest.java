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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Round 3: with {@code craftpilot.runtime.final-consistency-check} on, the poll numbers and the "mentioned by"
 * counts in the final answer are checked against what the tools returned. A mismatch gets one corrective turn
 * (the correction is shown to the model); a second mismatch fails the run with INCONSISTENT_FINAL. The outcome is
 * recorded as a FINAL_CONSISTENCY runtime event: PASSED, CORRECTED_SAME_RECOMMENDATION (only the numbers changed),
 * CORRECTED_CHANGED_RECOMMENDATION (the conclusion changed too) or FAILED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class FinalConsistencyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-consistency";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String OWNER = "fc-owner";

    private static HttpServer openaiStub;
    private static HttpServer chatStub;
    private static final AtomicReference<String> activeRevision = new AtomicReference<>();
    /** Final answers the stub returns, in order, once the stats tool result is in the conversation. */
    private static final ConcurrentLinkedQueue<String> finals = new ConcurrentLinkedQueue<>();
    private static final List<String> chatRequests = new CopyOnWriteArrayList<>();

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
            chatRequests.add(request);
            if (!request.contains("\"role\":\"tool\"")) {
                respond(exchange, statsCallBody(activeRevision.get()));
                return;
            }
            String content = finals.size() > 1 ? finals.poll() : finals.peek();
            respond(exchange, finalBody(content));
        });
        chatStub.start();

        config.add("CRAFTPILOT_JDBC_URL", FinalConsistencyTest::jdbcUrl);
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
        config.add("craftpilot.outbox.dispatch-enabled", () -> "false");
        config.add("craftpilot.runtime.worker-enabled", () -> "false");
        config.add("craftpilot.runtime.final-consistency-check", () -> "true");
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

    static UUID merchant;
    static UUID surveyId;
    static UUID revision;
    static UUID pollId;
    /** Two opinions from author A, one from author B. */
    static UUID evidenceA1;
    static UUID evidenceA2;
    static UUID evidenceB1;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio, @Autowired TestRestTemplate rest) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'Consistency Merchant')").param("id", merchant).update();
        UUID owner = principal(jdbc, encoder, OWNER);
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();

        UUID group = id(post(rest, OWNER, "/merchants/" + merchant + "/groups", Map.of("name", "FcRoom")));
        UUID campaign = id(post(rest, OWNER, "/merchants/" + merchant + "/campaigns", Map.of("name", "FcCamp")));
        UUID style = id(post(rest, OWNER, "/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Scarf", "category", "accessory")));
        revision = revision(rest, style);
        surveyId = id(post(rest, OWNER, "/merchants/" + merchant + "/groups/" + group + "/surveys",
                Map.of("revisionIds", List.of(revision))));

        // authors A and B post with the sample card (group ADMIN); C only votes
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("fc-author-a", "ADMIN");
        roles.put("fc-author-b", "ADMIN");
        roles.put("fc-voter-c", "MEMBER");
        for (var entry : roles.entrySet()) {
            UUID member = principal(jdbc, encoder, entry.getKey());
            jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'MEMBER')")
                    .param("m", merchant).param("p", member).update();
            put(rest, OWNER, "/merchants/" + merchant + "/groups/" + group + "/members/" + member,
                    Map.of("role", entry.getValue(), "enabled", true));
        }
        // purchase intent: 2 will buy (A, B), 1 will not (C)
        JsonNode intent = post(rest, OWNER, "/merchants/" + merchant + "/surveys/" + surveyId + "/polls", Map.of(
                "revisionId", revision, "kind", "PURCHASE_INTENT", "question", "开售后会买吗？",
                "options", List.of("会买", "不会买")));
        pollId = id(intent);
        vote(rest, "fc-author-a", intent, 0);
        vote(rest, "fc-author-b", intent, 0);
        vote(rest, "fc-voter-c", intent, 1);

        evidenceA1 = message(jdbc, rest, group, "fc-author-a", "就是有一丢丢掉毛");
        evidenceA2 = message(jdbc, rest, group, "fc-author-a", "洗了一次还是掉毛");
        evidenceB1 = message(jdbc, rest, group, "fc-author-b", "颜色好看，开售就买");
        activeRevision.set(revision.toString());
    }

    @BeforeEach
    void resetScript() {
        finals.clear();
        chatRequests.clear();
    }

    @Test
    void consistentAnswerPassesWithoutRetry() {
        finals.add(answer("PRODUCE", 2, 1, List.of()));

        UUID run = runResearch();

        assertThat(status(run)).isEqualTo("SUCCEEDED");
        assertThat(feedbackTurns(run)).isZero();
        assertThat(outcome(run)).isEqualTo("PASSED");
        assertThat(finalOutput(run).path("pollReadings")).hasSize(2);
    }

    @Test
    void wrongPollNumbersGetOneCorrectionAndOnlyTheNumbersChange() {
        finals.add(answer("TRIAL", 1, 2, List.of()));
        finals.add(answer("TRIAL", 2, 1, List.of()));

        UUID run = runResearch();

        assertThat(status(run)).isEqualTo("SUCCEEDED");
        assertThat(feedbackTurns(run)).isEqualTo(1);
        assertThat(outcome(run)).isEqualTo("CORRECTED_SAME_RECOMMENDATION");
        assertThat(finalOutput(run).path("recommendation").asText()).isEqualTo("TRIAL");
        // the correcting turn shows the model its previous answer and the real numbers
        String retryRequest = chatRequests.get(chatRequests.size() - 1);
        assertThat(retryRequest).contains("consistency check", "you wrote 1 votes, survey_get_stats returned 2");
        assertThat(retryEvent(run).path("firstRecommendation").asText()).isEqualTo("TRIAL");
    }

    @Test
    void correctionThatChangesTheConclusionIsCountedSeparately() {
        finals.add(answer("TRIAL", 1, 2, List.of()));
        finals.add(answer("PRODUCE", 2, 1, List.of()));

        UUID run = runResearch();

        assertThat(status(run)).isEqualTo("SUCCEEDED");
        assertThat(outcome(run)).isEqualTo("CORRECTED_CHANGED_RECOMMENDATION");
        assertThat(finalOutput(run).path("recommendation").asText()).isEqualTo("PRODUCE");
    }

    @Test
    void stillInconsistentAfterTheCorrectionFailsTheRun() {
        finals.add(answer("TRIAL", 1, 2, List.of()));
        finals.add(answer("TRIAL", 1, 2, List.of()));

        UUID run = runResearch();

        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(terminalReason(run)).isEqualTo("INCONSISTENT_FINAL");
        assertThat(feedbackTurns(run)).isEqualTo(1);
        assertThat(outcome(run)).isEqualTo("FAILED");
        // stats call, first answer, corrected answer: no second correction
        assertThat(chatRequests).hasSize(3);
    }

    @Test
    void concernMentionedByTwoNeedsEvidenceFromTwoDifferentAuthors() {
        Map<String, Object> sameAuthor = concern("掉毛", 2, evidenceA1, evidenceA2);
        finals.add(answer("REVISE", 2, 1, List.of(sameAuthor)));
        finals.add(answer("REVISE", 2, 1, List.of(sameAuthor)));
        UUID rejected = runResearch();
        assertThat(terminalReason(rejected)).isEqualTo("INCONSISTENT_FINAL");
        assertThat(chatRequests.get(chatRequests.size() - 1))
                .contains("mentionedBy is 2 but its evidence comes from 1 different author(s)");

        resetScript();
        finals.add(answer("REVISE", 2, 1, List.of(concern("掉毛", 2, evidenceA1, evidenceB1))));
        UUID accepted = runResearch();
        assertThat(status(accepted)).isEqualTo("SUCCEEDED");
        assertThat(outcome(accepted)).isEqualTo("PASSED");
    }

    // ---------------------------------------------------------------- helpers

    private String answer(String recommendation, int willBuy, int willNot, List<Map<String, Object>> concerns) {
        int total = 3;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pollReadings", List.of(
                Map.of("pollId", pollId.toString(), "option", "会买", "votes", willBuy, "total", total),
                Map.of("pollId", pollId.toString(), "option", "不会买", "votes", willNot, "total", total)));
        out.put("concerns", concerns);
        out.put("recommendation", recommendation);
        out.put("summary", "test answer");
        out.put("evidenceIds", List.of(evidenceA1.toString(), evidenceB1.toString()));
        return JSON.writeValueAsString(out);
    }

    private static Map<String, Object> concern(String text, int mentionedBy, UUID... evidence) {
        List<String> ids = new ArrayList<>();
        for (UUID id : evidence) {
            ids.add(id.toString());
        }
        return Map.of("text", text, "evidenceIds", ids, "mentionedBy", mentionedBy);
    }

    private UUID runResearch() {
        HttpHeaders headers = headers(OWNER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange("/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST, new HttpEntity<>(Map.of("revisionIds", List.of(revision),
                        "objective", "Decide whether to produce"), headers), JsonNode.class);
        UUID run = UUID.fromString(created.getBody().path("runId").asText());
        for (int i = 0; i < 5 && List.of("QUEUED", "RUNNING", "RECOVERING").contains(status(run)); i++) {
            worker.claimAndRun(1);
        }
        return run;
    }

    private String status(UUID run) {
        return jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", run).query(String.class).single();
    }

    private String terminalReason(UUID run) {
        return jdbc.sql("SELECT coalesce(terminal_reason, '') FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", run).query(String.class).single();
    }

    private JsonNode finalOutput(UUID run) {
        return JSON.readTree(jdbc.sql("""
                SELECT final_output_json::text FROM agent_run WHERE merchant_id = :m AND id = :run
                """).param("m", merchant).param("run", run).query(String.class).single());
    }

    private long feedbackTurns(UUID run) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM agent_turn
                WHERE merchant_id = :m AND run_id = :run AND consistency_feedback IS NOT NULL
                """).param("m", merchant).param("run", run).query(Long.class).single();
    }

    private String outcome(UUID run) {
        return event(run, "FINAL_CONSISTENCY").path("outcome").asText();
    }

    private JsonNode retryEvent(UUID run) {
        return event(run, "FINAL_CONSISTENCY_RETRY");
    }

    private JsonNode event(UUID run, String type) {
        List<String> payloads = jdbc.sql("""
                SELECT payload_json::text FROM runtime_event
                WHERE merchant_id = :m AND run_id = :run AND event_type = :type ORDER BY seq
                """).param("m", merchant).param("run", run).param("type", type).query(String.class).list();
        assertThat(payloads).as("%s events", type).hasSize(1);
        return JSON.readTree(payloads.get(0));
    }

    private static UUID message(JdbcClient jdbc, TestRestTemplate rest, UUID group, String login, String text) {
        UUID messageId = id(post(rest, login, "/merchants/" + merchant + "/groups/" + group + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(), "text", text, "card", Map.of("revisionId", revision))));
        return jdbc.sql("""
                SELECT e.id FROM evidence_ref e
                JOIN feedback_opinion o ON o.merchant_id = e.merchant_id AND o.id = e.source_id
                WHERE e.merchant_id = :m AND e.survey_id = :s AND e.source_type = 'FEEDBACK_OPINION'
                  AND o.message_id = :msg
                ORDER BY e.seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).param("msg", messageId).query(UUID.class).single();
    }

    private static UUID principal(JdbcClient jdbc, PasswordEncoder encoder, String login) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO principal(id,login,display_name) VALUES (:id,:login,:login)")
                .param("id", id).param("login", login).update();
        jdbc.sql("""
                INSERT INTO dev_credential(principal_id,password_hash,created_at,expires_at)
                VALUES (:p,:h,clock_timestamp()-interval '1 day', clock_timestamp()+interval '1 day')
                """).param("p", id).param("h", encoder.encode(PASSWORD)).update();
        return id;
    }

    private static UUID revision(TestRestTemplate rest, UUID style) {
        UUID asset = uploadAsset(rest);
        return id(post(rest, OWNER, "/merchants/" + merchant + "/styles/" + style + "/revisions", Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "WOL-1", "materialName", "Wool",
                        "quantity", 1, "unit", "pc", "costGap", false)),
                "notes", "v1")));
    }

    private static void vote(TestRestTemplate rest, String login, JsonNode poll, int option) {
        UUID optionId = UUID.fromString(poll.path("options").get(option).path("id").asText());
        put(rest, login, "/merchants/" + merchant + "/surveys/" + surveyId + "/polls/" + id(poll) + "/vote",
                Map.of("optionId", optionId));
    }

    private static UUID id(JsonNode body) {
        return UUID.fromString(body.path("id").asText());
    }

    private static JsonNode post(TestRestTemplate rest, String login, String path, Object body) {
        var response = rest.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, headers(login)),
                JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("POST %s -> %s", path, response.getBody()).isTrue();
        return response.getBody();
    }

    private static void put(TestRestTemplate rest, String login, String path, Object body) {
        var response = rest.exchange("/api/v1" + path, HttpMethod.PUT, new HttpEntity<>(body, headers(login)),
                JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("PUT %s -> %s", path, response.getBody()).isTrue();
    }

    private static HttpHeaders headers(String login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(login, PASSWORD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static UUID uploadAsset(TestRestTemplate rest) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource(("fc-img-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "fc.png";
            }
        });
        HttpHeaders headers = headers(OWNER);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String statsCallBody(String revisionId) {
        Map<String, Object> call = Map.of("id", "call_stats", "type", "function", "function",
                Map.of("name", "survey_get_stats", "arguments", "{\"revisionId\":\"" + revisionId + "\"}"));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(call));
        return JSON.writeValueAsString(Map.of("id", "chatcmpl-stats",
                "choices", List.of(Map.of("finish_reason", "tool_calls", "message", message)),
                "usage", Map.of("prompt_tokens", 500, "completion_tokens", 50)));
    }

    private static String finalBody(String content) {
        return JSON.writeValueAsString(Map.of("id", "chatcmpl-final",
                "choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 500, "completion_tokens", 80)));
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
