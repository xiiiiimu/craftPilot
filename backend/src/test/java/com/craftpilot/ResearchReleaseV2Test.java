package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.craftpilot.runtime.release.AgentSpecResolver;
import com.craftpilot.runtime.worker.RunWorker;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

/**
 * survey_get_stats v2 returns the poll results (preference and purchase intent) for the authorized revision, so the
 * research agent can tell "likes it" from "will buy it". Polls of other revisions in the same survey are not
 * returned, and the invocation records the tool version that produced the result. The research prompt is v2 by
 * default and older versions stay resolvable for controlled experiments; the final answer is requested as a strict
 * JSON Schema (P1-3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class ResearchReleaseV2Test {
    private static final String MINIO_USER = "test-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-polls";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String OWNER = "polls-owner";

    private static HttpServer openaiStub;
    private static HttpServer chatStub;
    private static final AtomicReference<String> activeRevision = new AtomicReference<>();
    private static final AtomicReference<String> activeEvidence = new AtomicReference<>();
    private static final AtomicReference<String> lastChatRequest = new AtomicReference<>();

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
            lastChatRequest.set(request);
            boolean hasToolResult = request.contains("\"role\":\"tool\"");
            String usage = ",\"usage\":{\"prompt_tokens\":500,\"completion_tokens\":50}";
            respond(exchange, hasToolResult ? finalBody(activeEvidence.get(), usage)
                    : statsCallBody(activeRevision.get(), usage));
        });
        chatStub.start();

        config.add("CRAFTPILOT_JDBC_URL", ResearchReleaseV2Test::jdbcUrl);
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
        config.add("craftpilot.runtime.structured-final-output", () -> "true");
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
    @Autowired ObjectMapper mapper;
    @Autowired AgentSpecResolver specs;
    @Autowired MeterRegistry meters;

    static UUID merchant;
    static UUID surveyId;
    static UUID revisionV1;

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio, @Autowired TestRestTemplate rest) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'Polls Merchant')").param("id", merchant).update();
        UUID owner = principal(jdbc, encoder, OWNER);
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchant).param("p", owner).update();

        UUID group = id(post(rest, OWNER, "/merchants/" + merchant + "/groups", Map.of("name", "PollRoom")));
        UUID campaign = id(post(rest, OWNER, "/merchants/" + merchant + "/campaigns", Map.of("name", "PollCamp")));
        UUID style = id(post(rest, OWNER, "/merchants/" + merchant + "/campaigns/" + campaign + "/styles",
                Map.of("name", "Mug", "category", "home")));
        revisionV1 = revision(rest, style, null);
        UUID revisionV2 = revision(rest, style, revisionV1);
        surveyId = id(post(rest, OWNER, "/merchants/" + merchant + "/groups/" + group + "/surveys",
                Map.of("revisionIds", List.of(revisionV1, revisionV2))));

        String[] voters = new String[3];
        for (int i = 0; i < voters.length; i++) {
            voters[i] = "polls-voter-" + i;
            UUID voter = principal(jdbc, encoder, voters[i]);
            jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'MEMBER')")
                    .param("m", merchant).param("p", voter).update();
            put(rest, OWNER, "/merchants/" + merchant + "/groups/" + group + "/members/" + voter,
                    Map.of("role", "MEMBER", "enabled", true));
        }
        // V1 purchase intent: 1 will buy, 2 will not, 0 undecided
        JsonNode intent = poll(rest, revisionV1, "PURCHASE_INTENT", "开售后会买吗？", List.of("会买", "不会买", "再看看"));
        vote(rest, voters[0], intent, 0);
        vote(rest, voters[1], intent, 1);
        vote(rest, voters[2], intent, 1);
        // V2 preference: must not show up when the agent asks about V1
        JsonNode other = poll(rest, revisionV2, "PREFERENCE", "V2 喜欢吗？", List.of("喜欢", "一般"));
        vote(rest, voters[0], other, 0);

        post(rest, OWNER, "/merchants/" + merchant + "/groups/" + group + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(), "text", "好看但不会买", "card", Map.of("revisionId", revisionV1)));
        UUID evidence = jdbc.sql("""
                SELECT id FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s ORDER BY seq LIMIT 1
                """).param("m", merchant).param("s", surveyId).query(UUID.class).single();
        activeRevision.set(revisionV1.toString());
        activeEvidence.set(evidence.toString());
    }

    @Test
    void surveyStatsReturnsPollResultsOfTheAuthorizedRevisionOnly() throws Exception {
        UUID run = runResearch();

        Map<String, Object> invocation = jdbc.sql("""
                SELECT tool_version, status, result_json::text AS body FROM tool_invocation
                WHERE merchant_id = :m AND run_id = :run AND tool_name = 'survey_get_stats'
                """).param("m", merchant).param("run", run).query().singleRow();
        assertThat(invocation.get("status")).isEqualTo("SUCCEEDED");
        assertThat(invocation.get("tool_version")).isEqualTo("2");

        JsonNode body = mapper.readTree(String.valueOf(invocation.get("body")));
        assertThat(body.path("revisionId").asText()).isEqualTo(revisionV1.toString());
        assertThat(body.has("openIssues")).isTrue();
        assertThat(body.path("activeEvidence").asInt()).isEqualTo(1);

        JsonNode polls = body.path("polls");
        assertThat(polls.size()).isEqualTo(1);
        JsonNode poll = polls.get(0);
        assertThat(poll.path("kind").asText()).isEqualTo("PURCHASE_INTENT");
        assertThat(poll.path("question").asText()).isEqualTo("开售后会买吗？");
        assertThat(poll.path("totalVotes").asInt()).isEqualTo(3);
        assertThat(poll.path("options").size()).isEqualTo(3);
        assertThat(poll.path("options").get(0).path("label").asText()).isEqualTo("会买");
        assertThat(poll.path("options").get(0).path("votes").asInt()).isEqualTo(1);
        assertThat(poll.path("options").get(1).path("label").asText()).isEqualTo("不会买");
        assertThat(poll.path("options").get(1).path("votes").asInt()).isEqualTo(2);
        assertThat(poll.path("options").get(2).path("label").asText()).isEqualTo("再看看");
        assertThat(poll.path("options").get(2).path("votes").asInt()).isZero();
    }

    @Test
    void researchPromptIsLatestByDefaultAndOlderVersionsStayResolvable() {
        AgentSpecResolver.ResolvedSpec latest = specs.research();
        assertThat(latest.version()).isEqualTo("2");
        assertThat(latest.instructions()).contains("PURCHASE_INTENT", "TRIAL", "HOLD");

        AgentSpecResolver.ResolvedSpec v1 = specs.resolve(AgentSpecResolver.RESEARCH_AGENT, Optional.of("1"));
        assertThat(v1.contentHash()).isEqualTo("sha256:research-agent-r3-v1");
        assertThat(v1.instructions()).startsWith("You are CraftPilot research agent.").doesNotContain("PURCHASE_INTENT");

        assertThatThrownBy(() -> specs.resolve(AgentSpecResolver.RESEARCH_AGENT, Optional.of("9")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no version 9");
    }

    @Test
    void promptV3CandidatesAreStoredAsRevisionsWithoutChangingTheDefault() {
        for (String version : List.of("3a", "3b", "3c")) {
            AgentSpecResolver.ResolvedSpec v3 = specs.resolve(AgentSpecResolver.RESEARCH_AGENT, Optional.of(version));
            assertThat(v3.contentHash()).isEqualTo("sha256:research-agent-r3-v" + version);
            assertThat(v3.instructions()).startsWith("You are CraftPilot research agent.")
                    .contains("Appeal", "Purchase intent", "Problem concentration", "Evidence sufficiency");
        }
        assertThat(specs.resolve(AgentSpecResolver.RESEARCH_AGENT, Optional.of("3c")).instructions())
                .contains("Polarized opinions");
        assertThat(specs.research().version()).isEqualTo("2");
    }

    @Test
    void promptV4IsStoredAsARevisionWithoutChangingTheDefault() {
        AgentSpecResolver.ResolvedSpec v4 = specs.resolve(AgentSpecResolver.RESEARCH_AGENT, Optional.of("4"));
        assertThat(v4.contentHash()).isEqualTo("sha256:research-agent-r3-v4");
        assertThat(v4.instructions()).startsWith("You are CraftPilot research agent.")
                .contains("pollReadings", "concerns", "mentionedBy", "Buying as a gift counts as buying")
                // v4 judges purchase intent as a whole instead of triggering TRIAL on keywords
                .doesNotContain("\"用不上\"", "\"放不下\"");
        assertThat(specs.research().version()).isEqualTo("2");
    }

    @Test
    void researchRunUsesTheLatestPromptAndAsksForStructuredFinalOutput() throws Exception {
        double parsedBefore = meters.counter("craftpilot.agent.final.parse", "loop", "research", "outcome", "parsed")
                .count();
        UUID run = runResearch();

        JsonNode format = mapper.readTree(lastChatRequest.get()).path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo("research_final");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("schema").path("required").toString())
                .contains("recommendation", "summary", "evidenceIds");
        assertThat(meters.counter("craftpilot.agent.final.parse", "loop", "research", "outcome", "parsed").count())
                .isEqualTo(parsedBefore + 1);

        String instructions = jdbc.sql("""
                SELECT messages_json->0->>'content' FROM context_snapshot WHERE merchant_id = :m AND run_id = :run
                ORDER BY created_at LIMIT 1
                """).param("m", merchant).param("run", run).query(String.class).single();
        assertThat(instructions).isEqualTo(specs.research().instructions());
    }

    private UUID runResearch() {
        HttpHeaders headers = headers(OWNER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = rest.exchange("/api/v1/merchants/" + merchant + "/surveys/" + surveyId + "/tickets",
                HttpMethod.POST, new HttpEntity<>(Map.of("revisionIds", List.of(revisionV1), "objective", "polls"),
                        headers), JsonNode.class);
        UUID run = UUID.fromString(created.getBody().path("runId").asText());
        worker.claimAndRun(1);
        assertThat(jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", run).query(String.class).single()).isEqualTo("SUCCEEDED");
        return run;
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

    private static UUID revision(TestRestTemplate rest, UUID style, UUID parent) {
        UUID asset = uploadAsset(rest);
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "assetIds", List.of(asset),
                "bomItems", List.of(Map.of("lineNo", 1, "materialCode", "CER-1", "materialName", "Clay",
                        "quantity", 1, "unit", "pc", "costGap", false)),
                "notes", parent == null ? "v1" : "v2"));
        if (parent != null) {
            body.put("parentRevisionId", parent);
        }
        return id(post(rest, OWNER, "/merchants/" + merchant + "/styles/" + style + "/revisions", body));
    }

    private static JsonNode poll(TestRestTemplate rest, UUID revision, String kind, String question,
                                 List<String> options) {
        return post(rest, OWNER, "/merchants/" + merchant + "/surveys/" + surveyId + "/polls", Map.of(
                "revisionId", revision, "kind", kind, "question", question, "options", options));
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
        body.add("file", new ByteArrayResource(("polls-img-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "polls.png";
            }
        });
        HttpHeaders headers = headers(OWNER);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var resp = rest.exchange("/api/v1/merchants/" + merchant + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        return UUID.fromString(resp.getBody().path("id").asText());
    }

    private static String statsCallBody(String revision, String usage) {
        String args = "{\\\"revisionId\\\":\\\"" + revision + "\\\"}";
        return "{\"id\":\"chatcmpl-stats\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":"
                + "\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"survey_get_stats\",\"arguments\":\"" + args + "\"}}]}}]" + usage + "}";
    }

    private static String finalBody(String evidence, String usage) {
        String content = "{\\\"recommendation\\\":\\\"TRIAL\\\",\\\"summary\\\":\\\"ok\\\",\\\"evidenceIds\\\":[\\\""
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
