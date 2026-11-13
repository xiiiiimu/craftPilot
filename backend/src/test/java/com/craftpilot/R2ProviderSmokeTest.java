package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
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
 * Live OpenAI Embedding + Qwen-VL smoke. Never stubs providers. Skips unless both API keys are present.
 * Results are written redacted to the repo docs path by the test (no secrets).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R2ProviderSmokeTest {
    private static final String MINIO_USER = "smoke-" + UUID.randomUUID();
    private static final String MINIO_PASSWORD = UUID.randomUUID().toString();
    private static final String BUCKET = "craftpilot-r2-smoke";
    private static final String PASSWORD = UUID.randomUUID().toString() + "-smoke-pass";
    private static final Path ENV_FILE = Path.of("..").toAbsolutePath().normalize().resolve(".env");
    private static final Path RESULTS = Path.of("..").toAbsolutePath().normalize()
            .resolve("docs/development/R2-provider-smoke-results.md");

    private static final Map<String, String> ENV = loadDotEnv(ENV_FILE);

    // Qwen-VL requires width/height > 10; use distinct 64x64 authorized sample images.
    private static final byte[] PNG_V1 = solidPng(64, 64, new Color(180, 40, 40));
    private static final byte[] PNG_V2 = solidPng(64, 64, new Color(40, 40, 180));

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
        String openaiKey = firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_API_KEY"), System.getenv("CRAFTPILOT_OPENAI_API_KEY"));
        String qwenKey = firstNonBlank(ENV.get("CRAFTPILOT_QWEN_API_KEY"), System.getenv("CRAFTPILOT_QWEN_API_KEY"));
        assumeTrue(openaiKey != null && !openaiKey.isBlank(), "CRAFTPILOT_OPENAI_API_KEY required");
        assumeTrue(qwenKey != null && !qwenKey.isBlank(), "CRAFTPILOT_QWEN_API_KEY required");

        config.add("CRAFTPILOT_JDBC_URL", R2ProviderSmokeTest::jdbcUrl);
        config.add("CRAFTPILOT_POSTGRES_USER", POSTGRES::getUsername);
        config.add("CRAFTPILOT_POSTGRES_PASSWORD", POSTGRES::getPassword);
        config.add("CRAFTPILOT_REDIS_HOST", REDIS::getHost);
        config.add("CRAFTPILOT_REDIS_PORT", REDIS::getFirstMappedPort);
        config.add("CRAFTPILOT_MINIO_ENDPOINT", R2ProviderSmokeTest::minioEndpoint);
        config.add("CRAFTPILOT_MINIO_ACCESS_KEY", () -> MINIO_USER);
        config.add("CRAFTPILOT_MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
        config.add("CRAFTPILOT_MINIO_BUCKET", () -> BUCKET);
        config.add("craftpilot.auth.dev-enabled", () -> true);
        config.add("craftpilot.outbox.dispatch-enabled", () -> false);
        // Live providers only — no stubs.
        config.add("craftpilot.openai.api-key", () -> openaiKey);
        config.add("craftpilot.openai.base-url",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_BASE_URL"), "https://api.openai.com"));
        config.add("craftpilot.openai.embedding-model",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_OPENAI_EMBEDDING_MODEL"), "text-embedding-3-small"));
        config.add("craftpilot.qwen.api-key", () -> qwenKey);
        config.add("craftpilot.qwen.base-url",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_QWEN_BASE_URL"),
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        config.add("craftpilot.qwen.vision-model",
                () -> firstNonBlank(ENV.get("CRAFTPILOT_QWEN_VISION_MODEL"), "qwen3-vl-plus"));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;

    private static UUID merchantId;
    private static UUID ownerId;
    private static UUID groupId;
    private static UUID revisionV1;
    private static UUID revisionV2;
    private static UUID surveyId;
    private static final Map<String, String> SMOKE = new LinkedHashMap<>();

    @BeforeAll
    static void fixtures(@Autowired JdbcClient jdbc, @Autowired PasswordEncoder encoder,
                         @Autowired MinioClient minio) throws Exception {
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        String hash = encoder.encode(PASSWORD);
        ownerId = user(jdbc, "r2-smoke-owner", hash);
        merchantId = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchant(id,name) VALUES (:id,'R2-Smoke')").param("id", merchantId).update();
        jdbc.sql("INSERT INTO merchant_member(merchant_id,principal_id,role_code) VALUES (:m,:p,'OWNER')")
                .param("m", merchantId).param("p", ownerId).update();
    }

    @Test
    @Order(1)
    void liveEmbeddingHybridRetrievalAndVisionCompare() throws Exception {
        groupId = UUID.fromString(post("/merchants/" + merchantId + "/groups",
                Map.of("name", "SmokeRoom")).getBody().path("id").asText());
        UUID campaignId = UUID.fromString(post("/merchants/" + merchantId + "/campaigns",
                Map.of("name", "SmokeCamp")).getBody().path("id").asText());
        UUID styleId = UUID.fromString(post("/merchants/" + merchantId + "/campaigns/" + campaignId + "/styles",
                Map.of("name", "SmokeStyle", "category", "sample")).getBody().path("id").asText());

        UUID asset1 = uploadAsset(PNG_V1);
        UUID asset2 = uploadAsset(PNG_V2);
        revisionV1 = UUID.fromString(post("/merchants/" + merchantId + "/styles/" + styleId + "/revisions", Map.of(
                "assetIds", List.of(asset1),
                "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v1-smoke")).getBody().path("id").asText());
        revisionV2 = UUID.fromString(post("/merchants/" + merchantId + "/styles/" + styleId + "/revisions", Map.of(
                "parentRevisionId", revisionV1,
                "assetIds", List.of(asset2),
                "bomItems", List.of(Map.of(
                        "lineNo", 1, "materialCode", "FAB-9", "materialName", "Cotton",
                        "quantity", 1, "unit", "m", "costGap", true)),
                "notes", "v2-smoke")).getBody().path("id").asText());

        surveyId = UUID.fromString(post("/merchants/" + merchantId + "/groups/" + groupId + "/surveys",
                Map.of("revisionIds", List.of(revisionV1, revisionV2))).getBody().path("id").asText());

        var card = post("/merchants/" + merchantId + "/groups/" + groupId + "/messages", Map.of(
                "clientMsgId", UUID.randomUUID(),
                "text", "喜欢这版面料 FAB-9",
                "card", Map.of("revisionId", revisionV2)));
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long evidenceBefore = jdbc.sql("""
                SELECT COUNT(*) FROM evidence_ref WHERE merchant_id = :m AND survey_id = :s
                """).param("m", merchantId).param("s", surveyId).query(Long.class).single();
        assertThat(evidenceBefore).isGreaterThan(0);

        var search = post("/merchants/" + merchantId + "/surveys/" + surveyId + "/retrieval/search", Map.of(
                "revisionId", revisionV2, "query", "FAB-9 fabric", "topK", 5));
        assertThat(search.getStatusCode())
                .as("embedding search status body=%s", search.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(search.getBody().path("retrievalMode").asText())
                .as("live embedding should enable hybrid or vector, not only lexical degrade")
                .isIn("hybrid", "vector", "lexical");
        assertThat(search.getBody().path("hits").size()).isGreaterThan(0);

        var embedCall = jdbc.sql("""
                SELECT model_name, status, input_tokens, provider_request_id, error_code
                FROM model_call_attempt
                WHERE merchant_id = :m AND purpose = 'embedding'
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchantId)
                .query((rs, row) -> Map.of(
                        "model", nullToEmpty(rs.getString("model_name")),
                        "status", nullToEmpty(rs.getString("status")),
                        "tokens", String.valueOf(rs.getObject("input_tokens")),
                        "requestId", redact(rs.getString("provider_request_id")),
                        "error", nullToEmpty(rs.getString("error_code"))))
                .single();
        assertThat(embedCall.get("status")).isEqualTo("SUCCEEDED");
        assertThat(embedCall.get("model")).isNotBlank();
        SMOKE.put("embedding_model", embedCall.get("model"));
        SMOKE.put("embedding_status", embedCall.get("status"));
        SMOKE.put("embedding_input_tokens", embedCall.get("tokens"));
        SMOKE.put("embedding_request_id_redacted", embedCall.get("requestId"));
        SMOKE.put("retrieval_mode", search.getBody().path("retrievalMode").asText());
        SMOKE.put("retrieval_hits", String.valueOf(search.getBody().path("hits").size()));

        long embeddedChunks = jdbc.sql("""
                SELECT COUNT(*) FROM evidence_chunk
                WHERE merchant_id = :m AND survey_id = :s AND revision_id = :r
                  AND embedding IS NOT NULL AND embedding_dim = 1536 AND visibility = 'ACTIVE'
                """).param("m", merchantId).param("s", surveyId).param("r", revisionV2)
                .query(Long.class).single();
        assertThat(embeddedChunks).as("pgvector embeddings persisted").isGreaterThan(0);
        SMOKE.put("pgvector_embedded_chunks", String.valueOf(embeddedChunks));

        var compare = post("/merchants/" + merchantId + "/vision/compare", Map.of(
                "leftRevisionId", revisionV1,
                "rightRevisionId", revisionV2,
                "surveyId", surveyId));
        assertThat(compare.getStatusCode())
                .as("vision compare status body=%s", compare.getBody())
                .isEqualTo(HttpStatus.OK);

        var visionCall = jdbc.sql("""
                SELECT model_name, status, http_status, input_tokens, output_tokens,
                       provider_request_id, error_code
                FROM model_call_attempt
                WHERE merchant_id = :m AND purpose = 'vision_compare'
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchantId)
                .query((rs, row) -> Map.of(
                        "model", nullToEmpty(rs.getString("model_name")),
                        "status", nullToEmpty(rs.getString("status")),
                        "http", String.valueOf(rs.getObject("http_status")),
                        "inTokens", String.valueOf(rs.getObject("input_tokens")),
                        "outTokens", String.valueOf(rs.getObject("output_tokens")),
                        "requestId", redact(rs.getString("provider_request_id")),
                        "error", nullToEmpty(rs.getString("error_code"))))
                .optional().orElse(Map.of(
                        "model", "", "status", "MISSING", "http", "", "inTokens", "",
                        "outTokens", "", "requestId", "(none)", "error", "NO_MODEL_CALL"));

        assertThat(compare.getBody().path("status").asText())
                .as("live Qwen-VL compare; apiStatus=%s http=%s error=%s body=%s",
                        visionCall.get("status"), visionCall.get("http"), visionCall.get("error"),
                        compare.getBody())
                .isEqualTo("SUCCEEDED");
        assertThat(compare.getBody().path("visibleDifferences").isArray()).isTrue();
        assertThat(visionCall.get("status")).isEqualTo("SUCCEEDED");
        SMOKE.put("vision_model", visionCall.get("model"));
        SMOKE.put("vision_status", visionCall.get("status"));
        SMOKE.put("vision_input_tokens", visionCall.get("inTokens"));
        SMOKE.put("vision_output_tokens", visionCall.get("outTokens"));
        SMOKE.put("vision_request_id_redacted", visionCall.get("requestId"));
        SMOKE.put("vision_compare_status", compare.getBody().path("status").asText());
        SMOKE.put("vision_diff_count", String.valueOf(compare.getBody().path("visibleDifferences").size()));
        SMOKE.put("vision_evidence_id_present",
                String.valueOf(!compare.getBody().path("evidenceId").isNull()));

        writeResults();
    }

    private void writeResults() throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# R2 provider smoke results\n\n");
        md.append("Generated by `R2ProviderSmokeTest` against live OpenAI Embedding and Qwen-VL.\n");
        md.append("Secrets are never written here.\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        for (var e : SMOKE.entrySet()) {
            md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        md.append("\n**Gate:** live embedding SUCCEEDED + live vision_compare SUCCEEDED + pgvector chunks embedded.\n");
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, md.toString(), StandardCharsets.UTF_8);
    }

    private UUID uploadAsset(byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("kind", "image");
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "smoke.png";
            }
        });
        var headers = new HttpHeaders();
        headers.setBasicAuth("r2-smoke-owner", PASSWORD);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/v1/merchants/" + merchantId + "/assets", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return auth().exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body), JsonNode.class);
    }

    private TestRestTemplate auth() {
        return http.withBasicAuth("r2-smoke-owner", PASSWORD);
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

    private static Map<String, String> loadDotEnv(Path path) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(path)) {
                return map;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                map.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
            }
        } catch (Exception ignored) {
            // Missing .env means smoke skips via assumeTrue.
        }
        return map;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        if (value.length() <= 8) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 8) + "…";
    }

    private static byte[] solidPng(int width, int height, Color color) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(color);
            g.fillRect(0, 0, width, height);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
